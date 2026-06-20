package com.vectrans.aimatrix.repository;

import com.vectrans.aimatrix.entity.TaskItem;
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
class TaskItemRepositoryTest {

    @Autowired
    private TaskItemRepository taskItemRepository;

    private Long savedTaskId;

    @BeforeAll
    void init() {
        taskItemRepository.deleteAll();
    }

    @AfterAll
    void cleanup() {
        taskItemRepository.deleteAll();
    }

    @Test
    @Order(1)
    void testSave() {
        System.out.println("\n========== 1. 保存任务验证 ==========");

        TaskItem saved = taskItemRepository.save(TaskItemDataProvider.projectDesign(LocalDate.now()));
        savedTaskId = saved.getId();

        assertNotNull(savedTaskId, "保存后 ID 不应为 null");
        assertNotNull(saved.getCreatedAt(), "createdAt 应由 @PrePersist 自动填充");
        assertEquals("PENDING", saved.getStatus(), "默认状态应为 PENDING");
        assertEquals(1, saved.getPriority(), "优先级应为 1");

        System.out.println("✅ 保存成功: " + saved);
    }

    @Test
    @Order(2)
    void testSaveBatchAndFindByFocusDate() {
        System.out.println("\n========== 2. 批量保存 + 按日期查询验证 ==========");

        LocalDate today = LocalDate.now();
        taskItemRepository.saveAll(TaskItemDataProvider.batchTasksForToday(today));

        List<TaskItem> todayTasks = taskItemRepository.findByFocusDate(today);
        System.out.println("今日任务数量: " + todayTasks.size());
        assertEquals(3, todayTasks.size(), "今日应有 3 条任务（Order1的1条 + 本测试的2条）");

        List<TaskItem> tomorrowTasks = taskItemRepository.findByFocusDate(today.plusDays(1));
        System.out.println("明日任务数量: " + tomorrowTasks.size());
        assertEquals(1, tomorrowTasks.size(), "明日任务应有 1 条");

        System.out.println("✅ 批量保存和日期查询正常");
    }

    @Test
    @Order(3)
    void testFindById() {
        System.out.println("\n========== 3. 按 ID 查询验证 ==========");

        Optional<TaskItem> found = taskItemRepository.findById(savedTaskId);
        assertTrue(found.isPresent(), "应能找到已保存的任务");
        assertEquals("完成项目方案设计", found.get().getTitle());

        System.out.println("✅ 按 ID 查询正常: " + found.get());
    }

    @Test
    @Order(4)
    void testUpdateStatus() {
        System.out.println("\n========== 4. 更新状态验证 ==========");

        TaskItem task = taskItemRepository.findById(savedTaskId).orElseThrow();
        task.setStatus("COMPLETED");
        TaskItem updated = taskItemRepository.save(task);

        assertEquals("COMPLETED", updated.getStatus(), "状态应已更新为 COMPLETED");
        assertNotNull(updated.getUpdatedAt(), "updatedAt 应由 @PreUpdate 自动更新");

        List<TaskItem> completedTasks = taskItemRepository.findByStatus("COMPLETED");
        assertEquals(1, completedTasks.size(), "应恰好有 1 条已完成任务");

        System.out.println("✅ 状态更新正常: " + updated);
    }

    @Test
    @Order(5)
    void testFindAll() {
        System.out.println("\n========== 5. 查询全部验证 ==========");

        List<TaskItem> allTasks = taskItemRepository.findAll();
        System.out.println("总任务数: " + allTasks.size());
        assertEquals(4, allTasks.size(), "总共应有 4 条任务");

        allTasks.forEach(task -> System.out.println("  - " + task));
        System.out.println("✅ 查询全部正常");
    }

    @Test
    @Order(6)
    void testDelete() {
        System.out.println("\n========== 6. 删除验证 ==========");

        long countBefore = taskItemRepository.count();
        taskItemRepository.deleteById(savedTaskId);
        long countAfter = taskItemRepository.count();

        assertEquals(countBefore - 1, countAfter, "删除后数量应减 1");
        assertTrue(taskItemRepository.findById(savedTaskId).isEmpty(), "删除后应查不到该任务");

        System.out.println("✅ 删除正常 (删除前: " + countBefore + ", 删除后: " + countAfter + ")");
    }
}
