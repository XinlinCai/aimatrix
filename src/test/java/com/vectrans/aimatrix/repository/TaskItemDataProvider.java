package com.vectrans.aimatrix.repository;

import com.vectrans.aimatrix.entity.TaskItem;

import java.time.LocalDate;
import java.util.List;

final class TaskItemDataProvider {

    private TaskItemDataProvider() {
    }

    static TaskItem projectDesign(LocalDate focusDate) {
        return new TaskItem("完成项目方案设计", "编写任务规划Agent的技术方案", 1, focusDate);
    }

    static TaskItem unitTest(LocalDate focusDate) {
        return new TaskItem("编写单元测试", "为Repository层编写集成测试", 2, focusDate);
    }

    static TaskItem codeReview(LocalDate focusDate) {
        return new TaskItem("代码评审", "审查PR中的代码变更", 3, focusDate);
    }

    static TaskItem weeklyReport(LocalDate focusDate) {
        return new TaskItem("准备周报", "总结本周工作进展", 1, focusDate);
    }

    static List<TaskItem> batchTasksForToday(LocalDate today) {
        return List.of(unitTest(today), codeReview(today), weeklyReport(today.plusDays(1)));
    }
}
