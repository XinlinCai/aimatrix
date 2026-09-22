package com.vectrans.aimatrix.context;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.vectrans.aimatrix.observability.ObservabilityRecorder;
import com.vectrans.aimatrix.service.AgentMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final String CONTEXT_KEY_SESSION_ID = "session_id";

    private final AgentMemoryService memoryService;
    private final int recallTopK;
    private final ObservabilityRecorder recorder;

    /**
     * 以「userId|query」为键的记忆检索缓存，减少同轮内的重复向量检索。
     * <p>
     * 使用 {@link ConcurrentHashMap} 而非「LinkedHashMap + synchronized」：本拦截器是单例，
     * 原来的方法级同步会让所有会话的检索全局串行化。
     */
    private final Map<String, CachedMemories> cache = new ConcurrentHashMap<>();

    public DynamicContextInterceptor(AgentMemoryService memoryService, int recallTopK) {
        this(memoryService, recallTopK, null);
    }

    public DynamicContextInterceptor(AgentMemoryService memoryService, int recallTopK,
                                     ObservabilityRecorder recorder) {
        this.memoryService = memoryService;
        this.recallTopK = recallTopK > 0 ? recallTopK : 5;
        this.recorder = recorder;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String sessionId = resolveContextValue(request, CONTEXT_KEY_SESSION_ID);
        try {
            String userId = resolveUserId(request);
            String query = resolveQuery(request);
            // 是否真的发起过召回：userId / query 任一缺失时不做检索，日志保持 '-' 而不误记为 miss
            boolean recallAttempted = StringUtils.hasText(userId) && StringUtils.hasText(query);
            String memoryContext = fetchMemories(userId, query);
            reportMemoryInjection(sessionId, memoryContext, recallAttempted);

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
            if (recorder != null) {
                recorder.recordContextDegrade(ObservabilityRecorder.CAUSE_MEMORY_INJECTION, sessionId);
            }
            return handler.call(request);
        }
    }

    /**
     * 上报本次长期记忆的召回结果，区分三种状态：
     * <ul>
     *   <li>未发起召回（缺 userId / query）→ 不上报，日志保持 {@code memRecall=-}；</li>
     *   <li>召回到内容 → 上报注入条数，日志为 {@code memRecall=hit}；</li>
     *   <li>发起召回但无可用内容（含检索降级）→ 记为 {@code memRecall=miss}。</li>
     * </ul>
     * 同一会话内的重复轮次由 {@link ObservabilityRecorder#recordMemoryInjection} 去重。
     */
    private void reportMemoryInjection(String sessionId, String memoryContext, boolean recallAttempted) {
        if (recorder == null || !recorder.isEnabled() || !recallAttempted) {
            return;
        }
        int lines = countMemoryLines(memoryContext);
        if (lines > 0) {
            recorder.recordMemoryInjection(sessionId, lines);
        } else {
            recorder.recordMemoryMiss(sessionId);
        }
    }

    /** 统计注入文本中的记忆条数（每行一条，格式为「- 记忆内容」） */
    private static int countMemoryLines(String memoryContext) {
        if (!StringUtils.hasText(memoryContext)) {
            return 0;
        }
        int lines = 0;
        for (String line : memoryContext.split("\n")) {
            if (StringUtils.hasText(line)) {
                lines++;
            }
        }
        return lines;
    }

    @Override
    public String getName() {
        return "DynamicContext";
    }

    /**
     * 将注入内容合并进系统消息：已有系统消息时追加，否则新建
     */
    private SystemMessage mergeSystemMessage(SystemMessage original, String injection) {
        // 空分析模式下表达式为「未注解」类型，向 @NonNull 参数传递前显式收敛为非空
        if (original != null && StringUtils.hasText(original.getText())) {
            String merged = original.getText() + injection;
            return new SystemMessage(Objects.requireNonNull(merged));
        }
        String trimmedInjection = Objects.requireNonNull(injection).trim();
        return new SystemMessage(Objects.requireNonNull(trimmedInjection));
    }

    private String resolveUserId(ModelRequest request) {
        return resolveContextValue(request, CONTEXT_KEY_USER_ID);
    }

    /** 从请求上下文（由 RunnableConfig.metadata 透传）中读取指定字段 */
    private String resolveContextValue(ModelRequest request, String key) {
        Map<String, Object> context = request == null ? null : request.getContext();
        if (context == null) {
            return null;
        }
        Object value = context.get(key);
        return value != null ? value.toString() : null;
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

    /**
     * 读取（未命中则回源）记忆检索结果。
     * <p>
     * 不做方法级同步：缓存本身线程安全，且向量检索在锁外执行，
     * 避免单例拦截器的全局锁把并发会话串行化；同键并发回源时以最后一次写入为准。
     */
    private String fetchMemories(String userId, String query) {
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
