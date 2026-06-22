package com.vectrans.aimatrix.repository;

import com.vectrans.aimatrix.entity.DailyPlan;
import com.vectrans.aimatrix.entity.TaskItem;
import com.vectrans.aimatrix.entity.enums.PlanStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings("null")
class DailyPlanRepositoryTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private DailyPlanRepository dailyPlanRepository;

    @Autowired
    private TaskItemRepository taskItemRepository;

    private Long savedPlanId;
    private TaskItem persistedTask;

    @BeforeAll
    void init() {
        // 先清理子表 daily_plan，再清理父表 task_item（外键约束顺序）
        dailyPlanRepository.deleteAll();
        taskItemRepository.deleteAll();

        // 预置一条任务供 daily_plan 引用
        persistedTask = taskItemRepository.save(
                DailyPlanDataProvider.sampleTask("每日计划测试任务")
        );
    }

    @AfterAll
    void cleanup() {
        dailyPlanRepository.deleteAll();
        taskItemRepository.deleteAll();
    }

    @Test
    @Order(1)
    void testSave() {
        System.out.println("\n========== 1. 保存每日计划验证 ==========");

        LocalDate today = LocalDate.now();
        DailyPlan plan = DailyPlanDataProvider.pendingPlan(today, persistedTask);
        DailyPlan saved = dailyPlanRepository.save(plan);
        savedPlanId = saved.getId();

        assertNotNull(savedPlanId, "保存后 ID 不应为 null");
        assertNotNull(saved.getCreatedAt(), "createdAt 应由 @PrePersist 自动填充");
        assertEquals(PlanStatus.PENDING, saved.getStatus(), "默认状态应为 PENDING");
        assertEquals(today, saved.getPlanDate(), "计划日期应为今天");
        assertEquals(persistedTask.getId(), saved.getTask().getId(), "关联任务 ID 应一致");

        System.out.println("✅ 保存成功: " + saved);
    }

    @Test
    @Order(2)
    void testSaveBatchAndFindByDate() {
        System.out.println("\n========== 2. 批量保存 + 按日期查询验证 ==========");

        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        // 再创建两条任务用于批量保存
        TaskItem task2 = taskItemRepository.save(DailyPlanDataProvider.sampleTask("批量任务A"));
        TaskItem task3 = taskItemRepository.save(DailyPlanDataProvider.sampleTask("批量任务B"));

        dailyPlanRepository.saveAll(List.of(
                DailyPlanDataProvider.pendingPlan(today, task2),
                DailyPlanDataProvider.pendingPlan(today, task3),
                DailyPlanDataProvider.pendingPlan(tomorrow, task2)
        ));

        List<DailyPlan> todayPlans = dailyPlanRepository.findByUserIdAndPlanDate(USER_ID, today);
        System.out.println("今日计划数: " + todayPlans.size());
        assertEquals(3, todayPlans.size(), "今日应有 3 条计划（Order1的1条 + 本测试的2条）");

        List<DailyPlan> tomorrowPlans = dailyPlanRepository.findByUserIdAndPlanDate(USER_ID, tomorrow);
        System.out.println("明日计划数: " + tomorrowPlans.size());
        assertEquals(1, tomorrowPlans.size(), "明日应有 1 条计划");

        System.out.println("✅ 批量保存和日期查询正常");
    }

    @Test
    @Order(3)
    void testFindById() {
        System.out.println("\n========== 3. 按 ID 查询验证 ==========");

        Optional<DailyPlan> found = dailyPlanRepository.findById(savedPlanId);
        assertTrue(found.isPresent(), "应能找到已保存的计划");
        assertEquals(persistedTask.getId(), found.get().getTask().getId(), "关联任务应正确加载");

        System.out.println("✅ 按 ID 查询正常: " + found.get());
    }

    @Test
    @Order(4)
    void testUpdateStatus() {
        System.out.println("\n========== 4. 更新状态验证 ==========");

        DailyPlan plan = dailyPlanRepository.findById(savedPlanId).orElseThrow();
        plan.setStatus(PlanStatus.COMPLETED);
        plan.setRemark("当天任务已完成");
        DailyPlan updated = dailyPlanRepository.save(plan);

        assertEquals(PlanStatus.COMPLETED, updated.getStatus(), "状态应已更新为 COMPLETED");
        assertEquals("当天任务已完成", updated.getRemark(), "备注应已保存");
        assertNotNull(updated.getUpdatedAt(), "updatedAt 应由 @PreUpdate 自动更新");

        LocalDate today = LocalDate.now();
        List<DailyPlan> completedPlans = dailyPlanRepository
                .findByUserIdAndPlanDateAndStatus(USER_ID, today, PlanStatus.COMPLETED);
        assertEquals(1, completedPlans.size(), "今日应恰好有 1 条已完成计划");

        System.out.println("✅ 状态更新正常: " + updated);
    }

    @Test
    @Order(5)
    void testFindByTaskId() {
        System.out.println("\n========== 5. 按任务 ID 查询验证 ==========");

        List<DailyPlan> plansForTask = dailyPlanRepository.findByTaskId(persistedTask.getId());
        System.out.println("该任务的计划数: " + plansForTask.size());
        assertEquals(1, plansForTask.size(), "该任务应关联 1 条计划");
        assertEquals(savedPlanId, plansForTask.get(0).getId(), "计划 ID 应匹配");

        System.out.println("✅ 按任务 ID 查询正常");
    }

    @Test
    @Order(6)
    void testFindAll() {
        System.out.println("\n========== 6. 查询全部验证 ==========");

        List<DailyPlan> allPlans = dailyPlanRepository.findAll();
        System.out.println("总计划数: " + allPlans.size());
        assertEquals(4, allPlans.size(), "总共应有 4 条计划");

        allPlans.forEach(plan -> System.out.println("  - " + plan));
        System.out.println("✅ 查询全部正常");
    }

    @Test
    @Order(7)
    void testDelete() {
        System.out.println("\n========== 7. 删除验证 ==========");

        long countBefore = dailyPlanRepository.count();
        dailyPlanRepository.deleteById(savedPlanId);
        long countAfter = dailyPlanRepository.count();

        assertEquals(countBefore - 1, countAfter, "删除后数量应减 1");
        assertTrue(dailyPlanRepository.findById(savedPlanId).isEmpty(), "删除后应查不到该计划");

        System.out.println("✅ 删除正常 (删除前: " + countBefore + ", 删除后: " + countAfter + ")");
    }
}
