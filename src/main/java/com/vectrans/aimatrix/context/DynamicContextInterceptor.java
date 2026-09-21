package com.vectrans.aimatrix.context;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.vectrans.aimatrix.service.AgentMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态上下文注入拦截器（模型调用前生效，流式/非流式通用）
 * <p>
 * 在每次模型调用前，将“运行时上下文（当前日期）”与“长期记忆（按 userId 隔离语义召回）”
 * 动态拼接到系统消息中，实现调用前信息环境的动态构建。
 * <p>
 * 该拦截器只影响单次请求的入参，不写回图状态，因此不会污染会话历史。
 * 所有外部依赖（向量检索）均做异常降级与结果缓存，保证不拖垮主链路。
 */
public class DynamicContextInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DynamicContextInterceptor.class);

    /** 记忆检索结果的缓存有效期（毫秒） */
    private static final long CACHE_TTL_MILLIS = 60_000L;

    /** 记忆检索缓存的最大条目数，超出后整体清空 */
    private static final int CACHE_MAX_ENTRIES = 256;

    private static final String CONTEXT_KEY_USER_ID = "user_id";

    private final AgentMemoryService memoryService;
    private final int recallTopK;

    /** 以「userId|query」为键的记忆检索缓存，减少同轮内的重复向量检索 */
    private final Map<String, CachedMemories> cache = new LinkedHashMap<>();

    public DynamicContextInterceptor(AgentMemoryService memoryService, int recallTopK) {
        this.memoryService = memoryService;
        this.recallTopK = recallTopK > 0 ? recallTopK : 5;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        try {
            String userId = resolveUserId(request);
            String query = resolveQuery(request);
            String memoryContext = fetchMemories(userId, query);

            StringBuilder injection = new StringBuilder();
            injection.append("\n\n【运行时上下文】\n当前日期：").append(LocalDate.now());
            if (StringUtils.hasText(memoryContext)) {
                injection.append("\n\n【长期记忆】\n").append(memoryContext);
            }

            SystemMessage systemMessage = mergeSystemMessage(request.getSystemMessage(), injection.toString());
            ModelRequest enrichedRequest = ModelRequest.builder(request)
                    .systemMessage(systemMessage)
                    .build();
            return handler.call(enrichedRequest);
        } catch (Exception e) {
            log.warn("动态上下文注入失败，降级为原始请求：{}", e.getMessage());
            return handler.call(request);
        }
    }

    @Override
    public String getName() {
        return "DynamicContext";
    }

    /**
     * 将注入内容合并进系统消息：已有系统消息时追加，否则新建
     */
    private SystemMessage mergeSystemMessage(SystemMessage original, String injection) {
        if (original != null && StringUtils.hasText(original.getText())) {
            return new SystemMessage(original.getText() + injection);
        }
        return new SystemMessage(injection.trim());
    }

    private String resolveUserId(ModelRequest request) {
        Map<String, Object> context = request.getContext();
        if (context == null) {
            return null;
        }
        Object userId = context.get(CONTEXT_KEY_USER_ID);
        return userId != null ? userId.toString() : null;
    }

    private String resolveQuery(ModelRequest request) {
        List<Message> messages = request.getMessages();
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage userMessage) {
                return userMessage.getText();
            }
        }
        return "";
    }

    private synchronized String fetchMemories(String userId, String query) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(query)) {
            return "";
        }
        String cacheKey = userId + "|" + query;
        long now = System.currentTimeMillis();
        CachedMemories cached = cache.get(cacheKey);
        if (cached != null && now - cached.timestamp < CACHE_TTL_MILLIS) {
            return cached.content;
        }
        String content = memoryService.buildMemoryContext(userId, query, recallTopK);
        if (content == null) {
            content = "";
        }
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(cacheKey, new CachedMemories(content, now));
        return content;
    }

    /**
     * 记忆检索结果缓存条目
     */
    private static class CachedMemories {

        private final String content;
        private final long timestamp;

        CachedMemories(String content, long timestamp) {
            this.content = content;
            this.timestamp = timestamp;
        }
    }
}
