package com.vectrans.aimatrix.service;

import com.vectrans.aimatrix.entity.DailyPlan;
import com.vectrans.aimatrix.entity.TaskItem;
import com.vectrans.aimatrix.entity.enums.PlanStatus;
import com.vectrans.aimatrix.entity.enums.TaskStatus;
import com.vectrans.aimatrix.repository.DailyPlanRepository;
import com.vectrans.aimatrix.repository.TaskItemRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskPlanService 集成测试
 * 覆盖五条业务线：任务收纳、每日计划、状态变更、任务查询、复盘分析
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings("null")
class TaskPlanServiceTest {

    @Autowired
    private TaskPlanService taskPlanService;

    @Autowired
    private TaskItemRepository taskItemRepository;

    @Autowired
    private DailyPlanRepository dailyPlanRepository;

    /** 测试过程中创建的计划ID，供后续测试引用 */
    private Long planId;

    @BeforeAll
    void init() {
        // 清理顺序：先子表后父表
        dailyPlanRepository.deleteAll();
        taskItemRepository.deleteAll();
    }

    @AfterAll
    void cleanup() {
        dailyPlanRepository.deleteAll();
        taskItemRepository.deleteAll();
    }

    // ==================== 1. 任务收纳 ====================

    @Test
    @Order(1)
    void testCollectTask() {
        System.out.println("\n========== 1. 任务收纳 ==========");

        TaskItem task = taskPlanService.collectTask("完成季度汇报PPT", true, false);

        assertNotNull(task.getId(), "收纳后任务ID不应为空");
        assertEquals("完成季度汇报PPT", task.getTitle());
        assertTrue(task.getIsImportant(), "应标记为重要");
        assertFalse(task.getIsUrgent(), "应标记为不紧急");
        assertEquals(TaskStatus.UNCOMPLETED, task.getStatus(), "默认状态应为UNCOMPLETED");

        System.out.println("✅ 任务收纳成功: " + task);
    }

    @Test
    @Order(2)
    void testCollectTaskEmptyTitle() {
        System.out.println("\n========== 2. 任务收纳-空标题校验 ==========");

        assertThrows(IllegalArgumentException.class,
                () -> taskPlanService.collectTask("", false, false),
                "空标题应抛出 IllegalArgumentException");
        assertThrows(IllegalArgumentException.class,
                () -> taskPlanService.collectTask("   ", false, false),
                "纯空格标题应抛出 IllegalArgumentException");

        System.out.println("✅ 空标题校验通过");
    }

    @Test
    @Order(3)
    void testCollectBatchTasks() {
        System.out.println("\n========== 3. 批量收纳任务 ==========");

        List<TaskItem> tasks = TaskPlanServiceTestDataProvider.mixedPriorityTasks();
        for (TaskItem t : tasks) {
            taskPlanService.collectTask(t.getTitle(), t.getIsImportant(), t.getIsUrgent());
        }

        List<TaskItem> allUncompleted = taskPlanService.getUncompletedTasks();
        // 1 (Order1) + 4 (本测试) = 5
        assertEquals(5, allUncompleted.size(), "应有5条未完成任务");

        System.out.println("✅ 批量收纳成功，当前未完成任务数: " + allUncompleted.size());
    }

    // ==================== 2. 每日计划 ====================

    @Test
    @Order(4)
    void testGetUncompletedTasks() {
        System.out.println("\n========== 4. 查询未完成任务 ==========");

        List<TaskItem> tasks = taskPlanService.getUncompletedTasks();
        assertFalse(tasks.isEmpty(), "应有未完成任务");
        tasks.forEach(t -> assertEquals(TaskStatus.UNCOMPLETED, t.getStatus(),
                "返回列表中所有任务状态应为UNCOMPLETED"));

        System.out.println("✅ 未完成任务数: " + tasks.size());
        tasks.forEach(t -> System.out.println("  - " + t));
    }

    @Test
    @Order(5)
    void testCreateDailyPlan() {
        System.out.println("\n========== 5. 创建每日计划 ==========");

        // 取前2条未完成任务来创建今日计划
        List<TaskItem> tasks = taskPlanService.getUncompletedTasks();
        List<Long> selectedIds = tasks.subList(0, 2).stream()
                .map(TaskItem::getId)
                .toList();

        List<DailyPlan> plans = taskPlanService.createDailyPlan(selectedIds);

        assertEquals(2, plans.size(), "应创建2条每日计划");
        plans.forEach(p -> {
            assertNotNull(p.getId());
            assertEquals(PlanStatus.PENDING, p.getStatus(), "新建计划默认状态应为PENDING");
            assertEquals(LocalDate.now(), p.getPlanDate(), "计划日期应为今天");
        });

        planId = plans.get(0).getId();
        System.out.println("✅ 每日计划创建成功:");
        plans.forEach(p -> System.out.println("  - " + p));
    }

    @Test
    @Order(6)
    void testCreateDailyPlanExceedLimit() {
        System.out.println("\n========== 6. 每日计划超限校验 ==========");

        // 今日已有2条，再传2条应失败（总共会超3条上限）
        List<TaskItem> tasks = taskPlanService.getUncompletedTasks();
        List<Long> extraIds = tasks.subList(2, 4).stream()
                .map(TaskItem::getId)
                .toList();

        assertThrows(IllegalStateException.class,
                () -> taskPlanService.createDailyPlan(extraIds),
                "超过每日上限应抛出 IllegalStateException");

        System.out.println("✅ 每日计划超限校验通过");
    }

    @Test
    @Order(7)
    void testCreateDailyPlanEmptyList() {
        System.out.println("\n========== 7. 每日计划空列表校验 ==========");

        assertThrows(IllegalArgumentException.class,
                () -> taskPlanService.createDailyPlan(List.of()),
                "空列表应抛出 IllegalArgumentException");
        assertThrows(IllegalArgumentException.class,
                () -> taskPlanService.createDailyPlan(null),
                "null列表应抛出 IllegalArgumentException");

        System.out.println("✅ 空列表校验通过");
    }

    @Test
    @Order(8)
    void testGetRecentPlanHistory() {
        System.out.println("\n========== 8. 获取近一周计划历史 ==========");

        List<DailyPlan> history = taskPlanService.getRecentPlanHistory();
        assertFalse(history.isEmpty(), "近一周应有计划记录");

        System.out.println("✅ 近一周计划记录数: " + history.size());
        history.forEach(p -> System.out.println("  - " + p));
    }

    // ==================== 3. 状态变更 ====================

    @Test
    @Order(9)
    void testCompletePlan() {
        System.out.println("\n========== 9. 标记计划完成 + 联动任务状态 ==========");

        DailyPlan completed = taskPlanService.completePlan(planId);

        assertEquals(PlanStatus.COMPLETED, completed.getStatus(), "计划状态应为COMPLETED");

        // 验证联动：对应的 task_item 也应被标记为 COMPLETED
        TaskItem linkedTask = taskItemRepository.findById(completed.getTask().getId()).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, linkedTask.getStatus(),
                "联动任务状态应同步为COMPLETED");

        System.out.println("✅ 计划完成 + 任务联动成功: " + completed);
    }

    @Test
    @Order(10)
    void testCompletePlanDuplicate() {
        System.out.println("\n========== 10. 重复完成校验 ==========");

        assertThrows(IllegalStateException.class,
                () -> taskPlanService.completePlan(planId),
                "重复标记完成应抛出 IllegalStateException");

        System.out.println("✅ 重复完成校验通过");
    }

    @Test
    @Order(11)
    void testCompletePlanNotFound() {
        System.out.println("\n========== 11. 不存在的计划ID校验 ==========");

        assertThrows(IllegalArgumentException.class,
                () -> taskPlanService.completePlan(99999L),
                "不存在的planId应抛出 IllegalArgumentException");

        System.out.println("✅ 不存在计划ID校验通过");
    }

    @Test
    @Order(12)
    void testAddPlanRemark() {
        System.out.println("\n========== 12. 添加计划备注 ==========");

        // 取第二条计划（尚未完成的那条）来添加备注
        List<DailyPlan> todayPlans = taskPlanService.queryDailyPlans(null);
        DailyPlan pendingPlan = todayPlans.stream()
                .filter(p -> p.getStatus() == PlanStatus.PENDING)
                .findFirst()
                .orElseThrow();

        DailyPlan updated = taskPlanService.addPlanRemark(pendingPlan.getId(), "任务进展顺利，预计明天收尾");

        assertEquals("任务进展顺利，预计明天收尾", updated.getRemark(), "备注内容应一致");
        System.out.println("✅ 备注添加成功: " + updated);
    }

    // ==================== 4. 任务查询 ====================

    @Test
    @Order(13)
    void testQueryTasksAll() {
        System.out.println("\n========== 13. 查询所有非删除任务 ==========");

        List<TaskItem> allTasks = taskPlanService.queryTasks(null);
        assertFalse(allTasks.isEmpty(), "应有任务数据");
        allTasks.forEach(t -> assertNotEquals(TaskStatus.DELETED, t.getStatus(),
                "不应包含已删除任务"));

        System.out.println("✅ 非删除任务数: " + allTasks.size());
    }

    @Test
    @Order(14)
    void testQueryTasksByStatus() {
        System.out.println("\n========== 14. 按状态查询任务 ==========");

        List<TaskItem> completed = taskPlanService.queryTasks("COMPLETED");
        completed.forEach(t -> assertEquals(TaskStatus.COMPLETED, t.getStatus()));

        List<TaskItem> uncompleted = taskPlanService.queryTasks("UNCOMPLETED");
        uncompleted.forEach(t -> assertEquals(TaskStatus.UNCOMPLETED, t.getStatus()));

        System.out.println("✅ 已完成: " + completed.size() + ", 未完成: " + uncompleted.size());
    }

    @Test
    @Order(15)
    void testQueryTasksInvalidStatus() {
        System.out.println("\n========== 15. 无效状态查询校验 ==========");

        assertThrows(IllegalArgumentException.class,
                () -> taskPlanService.queryTasks("INVALID_STATUS"),
                "无效状态应抛出 IllegalArgumentException");

        System.out.println("✅ 无效状态校验通过");
    }

    @Test
    @Order(16)
    void testQueryDailyPlans() {
        System.out.println("\n========== 16. 查询今日计划 ==========");

        List<DailyPlan> todayPlans = taskPlanService.queryDailyPlans(null);
        assertFalse(todayPlans.isEmpty(), "今日应有计划");
        todayPlans.forEach(p -> assertEquals(LocalDate.now(), p.getPlanDate()));

        System.out.println("✅ 今日计划数: " + todayPlans.size());
        todayPlans.forEach(p -> System.out.println("  - " + p));
    }

    @Test
    @Order(17)
    void testQueryDailyPlansByDate() {
        System.out.println("\n========== 17. 查询指定日期计划 ==========");

        // 查明天（应该没有数据）
        List<DailyPlan> tomorrowPlans = taskPlanService.queryDailyPlans(LocalDate.now().plusDays(1));
        assertTrue(tomorrowPlans.isEmpty(), "明天应无计划");

        System.out.println("✅ 指定日期查询正常，明日计划数: " + tomorrowPlans.size());
    }

    // ==================== 5. 复盘分析 ====================

    @Test
    @Order(18)
    void testGetWeeklyReviewData() {
        System.out.println("\n========== 18. 获取周复盘数据 ==========");

        Map<String, Object> data = taskPlanService.getWeeklyReviewData();

        assertNotNull(data.get("startDate"), "应包含startDate");
        assertNotNull(data.get("endDate"), "应包含endDate");
        assertNotNull(data.get("totalPlans"), "应包含totalPlans");
        assertNotNull(data.get("completedPlans"), "应包含completedPlans");
        assertNotNull(data.get("pendingPlans"), "应包含pendingPlans");
        assertNotNull(data.get("completionRate"), "应包含completionRate");

        int total = (int) data.get("totalPlans");
        int completed = (int) data.get("completedPlans");
        int pending = (int) data.get("pendingPlans");

        assertTrue(total > 0, "总计划数应大于0");
        assertEquals(total, completed + pending, "总数应等于已完成+未完成");

        String rate = (String) data.get("completionRate");
        assertTrue(rate.endsWith("%"), "完成率应以%结尾");

        System.out.println("✅ 周复盘数据:");
        System.out.println("  时间范围: " + data.get("startDate") + " ~ " + data.get("endDate"));
        System.out.println("  总计划: " + total + ", 已完成: " + completed + ", 未完成: " + pending);
        System.out.println("  完成率: " + rate);
    }
}
