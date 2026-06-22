package com.vectrans.aimatrix.repository;

import com.vectrans.aimatrix.entity.TaskItem;
import com.vectrans.aimatrix.entity.enums.TaskStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings("null")
class TaskItemRepositoryTest {

    private static final Long USER_ID = 1L;

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

        TaskItem saved = taskItemRepository.save(TaskItemDataProvider.importantAndUrgent());
        savedTaskId = saved.getId();

        assertNotNull(savedTaskId, "保存后 ID 不应为 null");
        assertNotNull(saved.getCreatedAt(), "createdAt 应由 @PrePersist 自动填充");
        assertEquals(TaskStatus.UNCOMPLETED, saved.getStatus(), "默认状态应为 UNCOMPLETED");
        assertTrue(saved.getIsImportant(), "应标记为重要");
        assertTrue(saved.getIsUrgent(), "应标记为紧急");

        System.out.println("✅ 保存成功: " + saved);
    }

    @Test
    @Order(2)
    void testSaveBatchAndFindByQuadrant() {
        System.out.println("\n========== 2. 批量保存 + 四象限查询验证 ==========");

        taskItemRepository.saveAll(TaskItemDataProvider.allQuadrantTasks());

        List<TaskItem> importantUrgent = taskItemRepository
                .findByUserIdAndIsImportantAndIsUrgent(USER_ID, true, true);
        System.out.println("重要且紧急任务数: " + importantUrgent.size());
        assertEquals(2, importantUrgent.size(), "应有 2 条重要且紧急任务");

        List<TaskItem> importantNotUrgent = taskItemRepository
                .findByUserIdAndIsImportantAndIsUrgent(USER_ID, true, false);
        System.out.println("重要不紧急任务数: " + importantNotUrgent.size());
        assertEquals(1, importantNotUrgent.size(), "应有 1 条重要不紧急任务");

        System.out.println("✅ 批量保存和四象限查询正常");
    }

    @Test
    @Order(3)
    void testFindById() {
        System.out.println("\n========== 3. 按 ID 查询验证 ==========");

        Optional<TaskItem> found = taskItemRepository.findById(savedTaskId);
        assertTrue(found.isPresent(), "应能找到已保存的任务");
        assertEquals("修复线上紧急Bug", found.get().getTitle());

        System.out.println("✅ 按 ID 查询正常: " + found.get());
    }

    @Test
    @Order(4)
    void testUpdateStatus() {
        System.out.println("\n========== 4. 更新状态验证 ==========");

        TaskItem task = taskItemRepository.findById(savedTaskId).orElseThrow();
        task.setStatus(TaskStatus.COMPLETED);
        TaskItem updated = taskItemRepository.save(task);

        assertEquals(TaskStatus.COMPLETED, updated.getStatus(), "状态应已更新为 COMPLETED");
        assertNotNull(updated.getUpdatedAt(), "updatedAt 应由 @PreUpdate 自动更新");

        List<TaskItem> completedTasks = taskItemRepository
                .findByUserIdAndStatus(USER_ID, TaskStatus.COMPLETED);
        assertEquals(1, completedTasks.size(), "应恰好有 1 条已完成任务");

        System.out.println("✅ 状态更新正常: " + updated);
    }

    @Test
    @Order(5)
    void testFindAll() {
        System.out.println("\n========== 5. 查询全部验证 ==========");

        List<TaskItem> allTasks = taskItemRepository.findAll();
        System.out.println("总任务数: " + allTasks.size());
        assertEquals(5, allTasks.size(), "总共应有 5 条任务");

        allTasks.forEach(task -> System.out.println("  - " + task));
        System.out.println("✅ 查询全部正常");
    }

    @Test
    @Order(6)
    void testLogicalDelete() {
        System.out.println("\n========== 6. 逻辑删除验证 ==========");

        TaskItem task = taskItemRepository.findById(savedTaskId).orElseThrow();
        task.setStatus(TaskStatus.DELETED);
        taskItemRepository.save(task);

        List<TaskItem> activeTasks = taskItemRepository
                .findByUserIdAndStatus(USER_ID, TaskStatus.UNCOMPLETED);
        assertTrue(activeTasks.stream().noneMatch(t -> t.getId().equals(savedTaskId)),
                "逻辑删除后不应出现在未完成任务中");

        System.out.println("✅ 逻辑删除正常");
    }
}
