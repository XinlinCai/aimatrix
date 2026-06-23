package com.vectrans.aimatrix.service;

import com.vectrans.aimatrix.entity.DailyPlan;
import com.vectrans.aimatrix.entity.TaskItem;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 智能任务规划助手核心业务接口
 * <p>
 * 覆盖五条业务线：任务收纳、每日计划、状态变更、任务查询、复盘分析
 */
public interface TaskPlanService {

    /**
     * 任务收纳：创建新任务
     *
     * @param title       任务标题（由 LLM 从自然语言解析）
     * @param isImportant 是否重要
     * @param isUrgent    是否紧急
     * @return 创建的任务
     */
    TaskItem collectTask(String title, boolean isImportant, boolean isUrgent);

    /**
     * 查询所有未完成任务（排除已逻辑删除）
     */
    List<TaskItem> getUncompletedTasks();

    /**
     * 获取近一周的计划历史（用于每日计划推荐和复盘）
     *
     * @return 近7天的 daily_plan 列表
     */
    List<DailyPlan> getRecentPlanHistory();

    /**
     * 创建每日计划（当天最多3条）
     *
     * @param taskIds 选中的任务ID列表（1~3条）
     * @return 创建的 daily_plan 列表
     */
    List<DailyPlan> createDailyPlan(List<Long> taskIds);

    /**
     * 将指定 daily_plan 标记为已完成，联动更新 task_item 状态
     *
     * @param planId daily_plan 的 ID
     * @return 更新后的 daily_plan
     */
    DailyPlan completePlan(Long planId);

    /**
     * 给指定 daily_plan 添加/更新备注
     *
     * @param planId daily_plan 的 ID
     * @param remark 备注内容
     * @return 更新后的 daily_plan
     */
    DailyPlan addPlanRemark(Long planId, String remark);

    /**
     * 查询任务列表
     *
     * @param status 任务状态（UNCOMPLETED / COMPLETED / DELETED），传 null 查所有非删除任务
     * @return 任务列表
     */
    List<TaskItem> queryTasks(String status);

    /**
     * 查询指定日期的每日计划
     *
     * @param date 日期，传 null 则查今天
     * @return 当天的 daily_plan 列表
     */
    List<DailyPlan> queryDailyPlans(LocalDate date);

    /**
     * 获取近一周复盘统计数据（简化版）
     *
     * @return 包含 totalPlans、completedPlans、completionRate 等统计信息
     */
    Map<String, Object> getWeeklyReviewData();
}
