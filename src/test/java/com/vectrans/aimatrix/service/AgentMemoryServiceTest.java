package com.vectrans.aimatrix.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentMemoryService 长期记忆集成测试
 * <p>
 * 使用真实 PgVector + 向量模型，验证记忆写入、语义召回与按 userId 的用户隔离。
 * 每次运行使用随机 userId，结束后按 userId 清理，避免污染向量库。
 */
@SuppressWarnings("null")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentMemoryServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryServiceTest.class);

    private static final String META_USER_ID = "userId";

    @Autowired
    private AgentMemoryService agentMemoryService;

    @Autowired
    private VectorStore vectorStore;

    private final String userId = "mem-test-" + UUID.randomUUID();
    private final String otherUserId = "mem-test-other-" + UUID.randomUUID();

    @AfterAll
    void cleanup() {
        deleteByUserId(userId);
        deleteByUserId(otherUserId);
    }

    @Test
    @Order(1)
    @DisplayName("写入长期记忆后可按语义召回")
    void rememberThenRecall() {
        System.out.println("\n========== 长期记忆：写入 → 语义召回 ==========");
        String content = "我习惯在每天早上处理最重要的三件事";
        agentMemoryService.remember(userId, content);
        log.info("已写入记忆 - userId: {}, content: {}", userId, content);

        List<String> memories = agentMemoryService.recall(userId, "我一般什么时候处理最重要的工作？", 5);
        System.out.println("召回结果: " + memories);

        assertFalse(memories.isEmpty(), "应能召回刚写入的记忆");
        assertTrue(memories.stream().anyMatch(memory -> memory.contains("最重要的三件事")),
                "召回结果应包含写入内容");
        System.out.println("✅ 记忆写入与语义召回正常");
    }

    @Test
    @Order(2)
    @DisplayName("按 userId 隔离，不召回其他用户记忆")
    void recallShouldIsolateByUserId() {
        System.out.println("\n========== 长期记忆：用户隔离 ==========");
        agentMemoryService.remember(userId, "我偏爱用深色主题的编辑器");

        List<String> otherMemories = agentMemoryService.recall(otherUserId, "我偏爱什么主题的编辑器？", 5);
        System.out.println("其他用户召回结果: " + otherMemories);

        assertTrue(otherMemories.isEmpty(), "不应召回其他用户的记忆");
        System.out.println("✅ 用户隔离正常");
    }

    @Test
    @Order(3)
    @DisplayName("buildMemoryContext 以条目格式输出")
    void buildMemoryContextShouldFormatEntries() {
        System.out.println("\n========== 长期记忆：上下文拼装 ==========");
        agentMemoryService.remember(userId, "我的项目代号是 AIMatrix");

        String context = agentMemoryService.buildMemoryContext(userId, "我的项目代号是什么？", 5);
        System.out.println("拼装结果:\n" + context);

        assertFalse(context.isBlank(), "应产出记忆上下文");
        assertTrue(context.startsWith("- "), "每条记忆应以 '- ' 开头");
        System.out.println("✅ 记忆上下文拼装正常");
    }

    @Test
    @DisplayName("空参数直接返回空结果")
    void recallWithBlankArgsShouldReturnEmpty() {
        assertTrue(agentMemoryService.recall("", "任意问题", 5).isEmpty(), "空 userId 应返回空");
        assertTrue(agentMemoryService.recall(userId, "", 5).isEmpty(), "空 query 应返回空");
    }

    private void deleteByUserId(String targetUserId) {
        try {
            Filter.Expression filter = new FilterExpressionBuilder().eq(META_USER_ID, targetUserId).build();
            vectorStore.delete(filter);
            log.info("已清理测试记忆 - userId: {}", targetUserId);
        } catch (Exception e) {
            log.warn("清理测试记忆失败 - userId: {}, error: {}", targetUserId, e.getMessage());
        }
    }
}
