package com.vectrans.aimatrix.service;

import com.vectrans.aimatrix.entity.DailyPlan;
import com.vectrans.aimatrix.entity.TaskItem;

import java.time.LocalDate;
import java.util.List;

/**
 * TaskPlanService 测试数据工厂
 */
final class TaskPlanServiceTestDataProvider {

    private static final Long DEFAULT_USER_ID = 1L;

    private TaskPlanServiceTestDataProvider() {
    }

    // ==================== TaskItem 构建 ====================

    static TaskItem task(String title) {
        return new TaskItem(DEFAULT_USER_ID, title);
    }

    static TaskItem importantUrgentTask(String title) {
        TaskItem t = new TaskItem(DEFAULT_USER_ID, title);
        t.setIsImportant(true);
        t.setIsUrgent(true);
        return t;
    }

    static TaskItem importantNotUrgentTask(String title) {
        TaskItem t = new TaskItem(DEFAULT_USER_ID, title);
        t.setIsImportant(true);
        t.setIsUrgent(false);
        return t;
    }

    static TaskItem notImportantUrgentTask(String title) {
        TaskItem t = new TaskItem(DEFAULT_USER_ID, title);
        t.setIsImportant(false);
        t.setIsUrgent(true);
        return t;
    }

    static List<TaskItem> mixedPriorityTasks() {
        return List.of(
                importantUrgentTask("修复线上紧急Bug"),
                importantNotUrgentTask("完成项目方案设计"),
                notImportantUrgentTask("回复客户邮件"),
                task("整理桌面文件")
        );
    }

    // ==================== DailyPlan 构建 ====================

    static DailyPlan plan(LocalDate date, TaskItem task) {
        return new DailyPlan(DEFAULT_USER_ID, date, task);
    }
}
