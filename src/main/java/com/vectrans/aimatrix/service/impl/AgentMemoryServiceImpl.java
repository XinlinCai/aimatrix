package com.vectrans.aimatrix.service.impl;

import com.vectrans.aimatrix.observability.ObservabilityRecorder;
import com.vectrans.aimatrix.service.AgentMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 长期记忆服务实现：基于 PgVector 的向量化记忆存取
 * <p>
 * 写入时以 userId 作为元数据，检索时通过元数据过滤表达式隔离用户，避免跨用户记忆污染。
 * 所有向量操作均做异常降级，保证记忆层故障不会阻断主对话链路。
 */
@Service
public class AgentMemoryServiceImpl implements AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryServiceImpl.class);

    private static final String META_USER_ID = "userId";
    private static final String META_TYPE = "type";
    private static final String META_CREATED_AT = "createdAt";
    private static final String TYPE_LONG_TERM = "long_term";

    private final VectorStore vectorStore;
    private final ObservabilityRecorder recorder;

    @Value("${agent.memory.recall-top-k:5}")
    private int defaultTopK;

    /** 检索相似度下限（<=0 表示不限制），用于过滤低相关的噪声记忆 */
    @Value("${agent.memory.similarity-threshold:0.5}")
    private double similarityThreshold;

    public AgentMemoryServiceImpl(VectorStore vectorStore, ObservabilityRecorder recorder) {
        this.vectorStore = vectorStore;
        this.recorder = recorder;
    }

    @Override
    public void remember(String userId, String content) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(content)) {
            return;
        }
        // 空分析模式下表达式为「未注解」类型，向 @NonNull 参数传递前显式收敛为非空
        String safeUserId = Objects.requireNonNull(userId);
        String safeContent = Objects.requireNonNull(content);
        try {
            Map<String, Object> metadata = Objects.requireNonNull(Map.of(
                    META_USER_ID, safeUserId,
                    META_TYPE, TYPE_LONG_TERM,
                    META_CREATED_AT, Instant.now().toString()));
            Document document = new Document(safeContent, metadata);
            vectorStore.add(Objects.requireNonNull(List.of(document)));
            log.debug("长期记忆已写入 - userId: {}, content: {}", safeUserId, safeContent);
        } catch (Exception e) {
            log.warn("长期记忆写入失败，忽略该记忆 - userId: {}, error: {}", safeUserId, e.getMessage());
        }
    }

    @Override
    public List<String> recall(String userId, String query, int topK) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(query)) {
            return List.of();
        }
        // 空分析模式下表达式为「未注解」类型，向 @NonNull 参数传递前显式收敛为非空
        String safeUserId = Objects.requireNonNull(userId);
        String safeQuery = Objects.requireNonNull(query);
        int limit = topK > 0 ? topK : defaultTopK;
        try {
            Filter.Expression filter = new FilterExpressionBuilder()
                    .eq(META_USER_ID, safeUserId)
                    .build();
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(safeQuery)
                    .topK(limit)
                    .similarityThreshold(similarityThreshold)
                    .filterExpression(filter)
                    .build();
            List<Document> documents = vectorStore.similaritySearch(searchRequest);
            if (documents == null || documents.isEmpty()) {
                reportRecall(0);
                return List.of();
            }
            List<String> memories = documents.stream()
                    .map(document -> Objects.requireNonNull(document).getText())
                    .filter(StringUtils::hasText)
                    .toList();
            reportRecall(memories.size());
            return memories;
        } catch (Exception e) {
            log.warn("长期记忆检索失败，降级为空 - userId: {}, error: {}", safeUserId, e.getMessage());
            reportRecallFailure();
            return List.of();
        }
    }

    /** 上报一次召回结果（hit=命中条数>0，miss=0） */
    private void reportRecall(int hitCount) {
        if (recorder != null) {
            recorder.recordMemoryRecall(hitCount);
        }
    }

    /** 检索异常：既计为未命中，也计一次上下文降级 */
    private void reportRecallFailure() {
        if (recorder != null) {
            recorder.recordMemoryRecall(0);
            recorder.recordContextDegrade(ObservabilityRecorder.CAUSE_MEMORY_RECALL, null);
        }
    }

    @Override
    public String buildMemoryContext(String userId, String query, int topK) {
        List<String> memories = recall(userId, query, topK);
        if (memories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String memory : memories) {
            sb.append("- ").append(memory).append('\n');
        }
        return sb.toString();
    }
}
