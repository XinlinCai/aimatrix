package com.vectrans.aimatrix.repository;

import com.vectrans.aimatrix.entity.TaskItem;

import java.util.List;

final class TaskItemDataProvider {

    private static final Long DEFAULT_USER_ID = 1L;

    private TaskItemDataProvider() {
    }

    static TaskItem importantAndUrgent() {
        TaskItem task = new TaskItem(DEFAULT_USER_ID, "修复线上紧急Bug");
        task.setIsImportant(true);
        task.setIsUrgent(true);
        return task;
    }

    static TaskItem importantNotUrgent() {
        TaskItem task = new TaskItem(DEFAULT_USER_ID, "完成项目方案设计");
        task.setIsImportant(true);
        task.setIsUrgent(false);
        return task;
    }

    static TaskItem notImportantButUrgent() {
        TaskItem task = new TaskItem(DEFAULT_USER_ID, "回复客户邮件");
        task.setIsImportant(false);
        task.setIsUrgent(true);
        return task;
    }

    static TaskItem notImportantNotUrgent() {
        TaskItem task = new TaskItem(DEFAULT_USER_ID, "整理桌面文件");
        task.setIsImportant(false);
        task.setIsUrgent(false);
        return task;
    }

    static List<TaskItem> allQuadrantTasks() {
        return List.of(
                importantAndUrgent(),
                importantNotUrgent(),
                notImportantButUrgent(),
                notImportantNotUrgent()
        );
    }
}
