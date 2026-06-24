package com.vectrans.aimatrix.controller;

import com.vectrans.aimatrix.dto.AgentRequest;
import com.vectrans.aimatrix.dto.AgentResponse;
import com.vectrans.aimatrix.entity.TaskItem;
import com.vectrans.aimatrix.entity.enums.TaskStatus;
import com.vectrans.aimatrix.repository.DailyPlanRepository;
import com.vectrans.aimatrix.repository.TaskItemRepository;
import com.vectrans.aimatrix.service.AgentService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 端到端核心业务流程集成测试
 * 验证完整链路：用户自然语言 → ReactAgent → LLM → TaskTools → DB
 * <p>
 * 前提：需要可访问的 DashScope API（.env 已配置 API Key）
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentE2ETest {

    private static final String SESSION_ID = "e2e-test-session";

    @Autowired
    private AgentService agentService;

    @Autowired
    private TaskItemRepository taskItemRepository;

    @Autowired
    private DailyPlanRepository dailyPlanRepository;

    @BeforeAll
    void init() {
        // 清理顺序：先子表后父表
        dailyPlanRepository.deleteAll();
        taskItemRepository.deleteAll();
        System.out.println("\n========== 清理测试数据完成 ==========");
    }

    @AfterAll
    void cleanup() {
        dailyPlanRepository.deleteAll();
        taskItemRepository.deleteAll();
        System.out.println("\n========== 测试清理完成 ==========");
    }

    // ==================== 1. 任务收纳 ====================

    @Test
    @Order(1)
    void testCollectTask() {
        System.out.println("\n========== E2E 1. 任务收纳 ==========");
        String message = "帮我记一个任务：明天下午三点开会讨论Q3产品方案，这个事很重要也很紧急";

        AgentResponse response = agentService.chat(new AgentRequest(message, SESSION_ID));
        printExchange(message, response);

        assertNotNull(response.getReply());
        assertFalse(response.getReply().isBlank());
        assertTrue(response.getReply().contains("任务") || response.getReply().contains("收纳"),
                "回复应提及任务收纳相关确认");

        // 验证数据库：应有1条未完成任务
        List<TaskItem> tasks = taskItemRepository.findByUserIdAndStatus(1L, TaskStatus.UNCOMPLETED);
        assertFalse(tasks.isEmpty(), "应收纳成功创建至少1条任务");
        System.out.println("✅ 数据库已创建任务: " + tasks.get(tasks.size() - 1));
    }

    @Test
    @Order(2)
    void testCollectMultipleTasks() {
        System.out.println("\n========== E2E 2. 批量任务收纳 ==========");

        String msg1 = "还有一个任务：这周五前写完技术设计文档，重要但不急";
        String msg2 = "另外帮我记一下：回复客户张总的邮件，这个比较急但不算重要";
        String msg3 = "再记一个：整理上周会议纪要";

        for (String msg : List.of(msg1, msg2, msg3)) {
            AgentResponse r = agentService.chat(new AgentRequest(msg, SESSION_ID));
            System.out.println("用户: " + msg);
            System.out.println("AI: " + r.getReply());
            System.out.println("---");
        }

        List<TaskItem> all = taskItemRepository.findByUserIdAndStatus(1L, TaskStatus.UNCOMPLETED);
        assertTrue(all.size() >= 4, "应有至少4条未完成任务");
        System.out.println("✅ 累计未完成任务: " + all.size() + " 条");
        all.forEach(t -> System.out.println("  - " + t.getTitle()
                + (Boolean.TRUE.equals(t.getIsImportant()) ? " [重要]" : "")
                + (Boolean.TRUE.equals(t.getIsUrgent()) ? " [紧急]" : "")));
    }

    // ==================== 3. 任务查询 ====================

    @Test
    @Order(3)
    void testQueryTasks() {
        System.out.println("\n========== E2E 3. 任务查询 ==========");
        String message = "我有哪些待办任务？";

        AgentResponse response = agentService.chat(new AgentRequest(message, SESSION_ID));
        printExchange(message, response);

        assertNotNull(response.getReply());
        assertTrue(response.getReply().contains("任务") || response.getReply().contains("待办"),
                "回复应包含任务相关信息");
        System.out.println("✅ 任务查询正常");
    }

    // ==================== 4. 每日计划 ====================

    @Test
    @Order(4)
    void testCreateDailyPlan() {
        System.out.println("\n========== E2E 4. 制定每日计划 ==========");
        String message = "帮我制定今日计划";

        // 第一次调用：Agent 应推荐任务列表等待确认
        AgentResponse response = agentService.chat(new AgentRequest(message, SESSION_ID));
        printExchange(message, response);

        assertNotNull(response.getReply());
        assertTrue(response.getReply().contains("计划") || response.getReply().contains("推荐")
                || response.getReply().contains("任务") || response.getReply().contains("安排"),
                "回复应包含计划推荐内容");
        System.out.println("✅ 每日计划推荐完成");
    }

    @Test
    @Order(5)
    void testConfirmDailyPlan() {
        System.out.println("\n========== E2E 5. 确认每日计划 ==========");
        // 查询当前未完成任务，取前2个的ID来模拟用户确认
        List<TaskItem> tasks = taskItemRepository.findByUserIdAndStatus(1L, TaskStatus.UNCOMPLETED);
        if (tasks.size() < 2) {
            System.out.println("⚠️ 任务不足2条，跳过确认测试");
            return;
        }
        Long id1 = tasks.get(0).getId();
        Long id2 = tasks.get(1).getId();
        String message = String.format("好的，就安排任务ID %d 和 %d 到今日计划", id1, id2);

        AgentResponse response = agentService.chat(new AgentRequest(message, SESSION_ID));
        printExchange(message, response);

        assertNotNull(response.getReply());
        assertTrue(response.getReply().contains("计划") || response.getReply().contains("成功")
                || response.getReply().contains("安排"),
                "回复应确认计划创建");
        System.out.println("✅ 每日计划确认完成");
    }

    // ==================== 6. 查询今日计划 ====================

    @Test
    @Order(6)
    void testQueryDailyPlans() {
        System.out.println("\n========== E2E 6. 查询今日计划 ==========");
        String message = "今天有哪些计划安排？";

        AgentResponse response = agentService.chat(new AgentRequest(message, SESSION_ID));
        printExchange(message, response);

        assertNotNull(response.getReply());
        assertTrue(response.getReply().contains("计划") || response.getReply().contains("今天")
                || response.getReply().contains("安排"),
                "回复应包含今日计划信息");
        System.out.println("✅ 今日计划查询正常");
    }

    // ==================== 7. 复盘分析 ====================

    @Test
    @Order(7)
    void testWeeklyReview() {
        System.out.println("\n========== E2E 7. 周复盘分析 ==========");
        String message = "帮我看看这周的复盘情况";

        AgentResponse response = agentService.chat(new AgentRequest(message, SESSION_ID));
        printExchange(message, response);

        assertNotNull(response.getReply());
        assertTrue(response.getReply().contains("复盘") || response.getReply().contains("完成")
                || response.getReply().contains("率") || response.getReply().contains("本周"),
                "回复应包含复盘分析内容");
        System.out.println("✅ 周复盘分析正常");
    }

    // ==================== 辅助方法 ====================

    private void printExchange(String userMsg, AgentResponse response) {
        System.out.println("用户: " + userMsg);
        System.out.println("AI: " + response.getReply());
        System.out.println("SessionId: " + response.getSessionId());
    }
}
