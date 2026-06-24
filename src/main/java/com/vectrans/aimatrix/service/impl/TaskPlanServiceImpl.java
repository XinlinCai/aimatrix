package com.vectrans.aimatrix.service.impl;

import com.vectrans.aimatrix.entity.DailyPlan;
import com.vectrans.aimatrix.entity.TaskItem;
import com.vectrans.aimatrix.entity.enums.PlanStatus;
import com.vectrans.aimatrix.entity.enums.TaskStatus;
import com.vectrans.aimatrix.repository.DailyPlanRepository;
import com.vectrans.aimatrix.repository.TaskItemRepository;
import com.vectrans.aimatrix.service.TaskPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能任务规划助手核心业务实现
 */
@Service
public class TaskPlanServiceImpl implements TaskPlanService {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanServiceImpl.class);

    /** 单用户开发阶段固定 userId */
    private static final long CURRENT_USER_ID = 1L;

    /** 每日计划最大条数 */
    private static final int MAX_DAILY_PLAN_COUNT = 3;

    private final TaskItemRepository taskItemRepository;
    private final DailyPlanRepository dailyPlanRepository;

    public TaskPlanServiceImpl(TaskItemRepository taskItemRepository,
                               DailyPlanRepository dailyPlanRepository) {
        this.taskItemRepository = taskItemRepository;
        this.dailyPlanRepository = dailyPlanRepository;
    }

    // ==================== 1. 任务收纳 ====================

    @Override
    @Transactional
    public TaskItem collectTask(String title, boolean isImportant, boolean isUrgent) {
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        TaskItem task = new TaskItem(CURRENT_USER_ID, title.trim());
        task.setIsImportant(isImportant);
        task.setIsUrgent(isUrgent);
        TaskItem saved = taskItemRepository.save(task);
        log.info("任务收纳成功: {}", saved);
        return saved;
    }

    // ==================== 2. 每日计划 ====================

    @Override
    public List<TaskItem> getUncompletedTasks() {
        return taskItemRepository.findByUserIdAndStatus(CURRENT_USER_ID, TaskStatus.UNCOMPLETED);
    }

    @Override
    public List<DailyPlan> getRecentPlanHistory() {
        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(7);
        return dailyPlanRepository.findByUserIdAndPlanDateBetween(CURRENT_USER_ID, weekAgo, today);
    }

    @Override
    @Transactional
    public List<DailyPlan> createDailyPlan(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new IllegalArgumentException("至少需要选择1个任务来创建每日计划");
        }
        if (taskIds.size() > MAX_DAILY_PLAN_COUNT) {
            throw new IllegalArgumentException("每日计划最多只能安排" + MAX_DAILY_PLAN_COUNT + "件事");
        }

        LocalDate today = LocalDate.now();
        List<DailyPlan> existingPlans = dailyPlanRepository.findByUserIdAndPlanDate(CURRENT_USER_ID, today);
        int availableSlots = MAX_DAILY_PLAN_COUNT - existingPlans.size();
        if (availableSlots <= 0) {
            throw new IllegalStateException("今日计划已满（最多" + MAX_DAILY_PLAN_COUNT + "件事），无法继续添加");
        }
        if (taskIds.size() > availableSlots) {
            throw new IllegalStateException("今日计划仅剩" + availableSlots + "个空位，无法安排" + taskIds.size() + "个任务");
        }

        List<DailyPlan> createdPlans = new ArrayList<>();
        for (Long taskId : taskIds) {
            Objects.requireNonNull(taskId, "任务ID不能为null");
            TaskItem task = taskItemRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("任务不存在，ID: " + taskId));
            if (task.getStatus() == TaskStatus.DELETED) {
                throw new IllegalArgumentException("任务已被删除，ID: " + taskId);
            }

            DailyPlan plan = new DailyPlan(CURRENT_USER_ID, today, task);
            createdPlans.add(dailyPlanRepository.save(plan));
        }
        log.info("创建每日计划成功，共{}条", createdPlans.size());
        return createdPlans;
    }

    // ==================== 3. 状态变更 ====================

    @Override
    @Transactional
    public DailyPlan completePlan(Long planId) {
        DailyPlan plan = dailyPlanRepository.findByIdWithTask(planId)
                .orElseThrow(() -> new IllegalArgumentException("每日计划不存在，ID: " + planId));

        if (plan.getStatus() == PlanStatus.COMPLETED) {
            throw new IllegalStateException("该计划已完成，不支持重复操作");
        }

        // 更新 daily_plan 状态
        plan.setStatus(PlanStatus.COMPLETED);
        DailyPlan updatedPlan = dailyPlanRepository.save(plan);

        // 联动更新：将对应的 task_item 也标记为 COMPLETED
        TaskItem task = plan.getTask();
        if (task != null && task.getStatus() != TaskStatus.COMPLETED) {
            task.setStatus(TaskStatus.COMPLETED);
            taskItemRepository.save(task);
            log.info("联动更新：任务[{}]状态已同步为COMPLETED", task.getId());
        }

        log.info("每日计划[{}]标记为COMPLETED", planId);
        return updatedPlan;
    }

    @Override
    @Transactional
    public DailyPlan addPlanRemark(Long planId, String remark) {
        DailyPlan plan = dailyPlanRepository.findByIdWithTask(planId)
                .orElseThrow(() -> new IllegalArgumentException("每日计划不存在，ID: " + planId));
        plan.setRemark(remark);
        DailyPlan updated = dailyPlanRepository.save(plan);
        log.info("每日计划[{}]备注已更新: {}", planId, remark);
        return updated;
    }

    // ==================== 4. 任务查询 ====================

    @Override
    public List<TaskItem> queryTasks(String status) {
        if (!StringUtils.hasText(status)) {
            // 查所有非删除任务
            return taskItemRepository.findByUserIdAndStatusNot(CURRENT_USER_ID, TaskStatus.DELETED);
        }
        try {
            TaskStatus taskStatus = TaskStatus.valueOf(status.toUpperCase());
            return taskItemRepository.findByUserIdAndStatus(CURRENT_USER_ID, taskStatus);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的任务状态: " + status
                    + "，有效值为: UNCOMPLETED, COMPLETED, DELETED");
        }
    }

    @Override
    public List<DailyPlan> queryDailyPlans(LocalDate date) {
        LocalDate queryDate = (date != null) ? date : LocalDate.now();
        return dailyPlanRepository.findByUserIdAndPlanDate(CURRENT_USER_ID, queryDate);
    }

    // ==================== 5. 复盘分析 ====================

    @Override
    public Map<String, Object> getWeeklyReviewData() {
        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(7);

        List<DailyPlan> allPlans = dailyPlanRepository.findByUserIdAndPlanDateBetween(
                CURRENT_USER_ID, weekAgo, today);
        List<DailyPlan> completedPlans = dailyPlanRepository.findByUserIdAndPlanDateBetweenAndStatus(
                CURRENT_USER_ID, weekAgo, today, PlanStatus.COMPLETED);

        int total = allPlans.size();
        int completed = completedPlans.size();
        double rate = (total > 0) ? Math.round((double) completed / total * 100) : 0;

        // 按日期分组统计
        Map<LocalDate, Long> dailyCompletedCount = completedPlans.stream()
                .collect(Collectors.groupingBy(DailyPlan::getPlanDate, Collectors.counting()));
        Map<LocalDate, Long> dailyTotalCount = allPlans.stream()
                .collect(Collectors.groupingBy(DailyPlan::getPlanDate, Collectors.counting()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", weekAgo);
        result.put("endDate", today);
        result.put("totalPlans", total);
        result.put("completedPlans", completed);
        result.put("pendingPlans", total - completed);
        result.put("completionRate", rate + "%");
        result.put("dailyCompletedCount", dailyCompletedCount);
        result.put("dailyTotalCount", dailyTotalCount);

        return result;
    }
}
