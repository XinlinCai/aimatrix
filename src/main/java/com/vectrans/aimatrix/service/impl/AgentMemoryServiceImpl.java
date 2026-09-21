package com.vectrans.aimatrix.service.impl;

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

    @Value("${agent.memory.recall-top-k:5}")
    private int defaultTopK;

    public AgentMemoryServiceImpl(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void remember(String userId, String content) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(content)) {
            return;
        }
        try {
            Document document = new Document(content, Map.of(
                    META_USER_ID, userId,
                    META_TYPE, TYPE_LONG_TERM,
                    META_CREATED_AT, Instant.now().toString()));
            vectorStore.add(List.of(document));
            log.debug("长期记忆已写入 - userId: {}, content: {}", userId, content);
        } catch (Exception e) {
            log.warn("长期记忆写入失败，忽略该记忆 - userId: {}, error: {}", userId, e.getMessage());
        }
    }

    @Override
    public List<String> recall(String userId, String query, int topK) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(query)) {
            return List.of();
        }
        int limit = topK > 0 ? topK : defaultTopK;
        try {
            Filter.Expression filter = new FilterExpressionBuilder()
                    .eq(META_USER_ID, userId)
                    .build();
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(limit)
                    .filterExpression(filter)
                    .build();
            List<Document> documents = vectorStore.similaritySearch(searchRequest);
            if (documents == null || documents.isEmpty()) {
                return List.of();
            }
            return documents.stream()
                    .map(Document::getText)
                    .filter(StringUtils::hasText)
                    .toList();
        } catch (Exception e) {
            log.warn("长期记忆检索失败，降级为空 - userId: {}, error: {}", userId, e.getMessage());
            return List.of();
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
