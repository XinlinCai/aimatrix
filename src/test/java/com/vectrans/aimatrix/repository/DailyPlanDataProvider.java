package com.vectrans.aimatrix.repository;

import com.vectrans.aimatrix.entity.DailyPlan;
import com.vectrans.aimatrix.entity.TaskItem;

import java.time.LocalDate;

final class DailyPlanDataProvider {

    private static final Long DEFAULT_USER_ID = 1L;

    private DailyPlanDataProvider() {
    }

    static TaskItem sampleTask(String title) {
        return new TaskItem(DEFAULT_USER_ID, title);
    }

    static DailyPlan pendingPlan(LocalDate planDate, TaskItem task) {
        return new DailyPlan(DEFAULT_USER_ID, planDate, task);
    }

    static DailyPlan completedPlan(LocalDate planDate, TaskItem task) {
        DailyPlan plan = new DailyPlan(DEFAULT_USER_ID, planDate, task);
        plan.setRemark("已完成");
        return plan;
    }
}
