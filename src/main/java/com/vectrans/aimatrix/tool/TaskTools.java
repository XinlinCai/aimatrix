package com.vectrans.aimatrix.tool;

import com.vectrans.aimatrix.entity.DailyPlan;
import com.vectrans.aimatrix.entity.TaskItem;
import com.vectrans.aimatrix.service.TaskPlanService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 智能任务规划助手 Agent 工具集
 * <p>
 * 通过 @Tool 注解暴露给 ReactAgent，由 LLM 根据用户意图自动调用
 */
@Component
public class TaskTools {

    private final TaskPlanService taskPlanService;

    public TaskTools(TaskPlanService taskPlanService) {
        this.taskPlanService = taskPlanService;
    }

    // ==================== 1. 任务收纳 ====================

    @Tool(description = "收纳新任务：将用户的口语化描述解析为结构化任务并存入系统。根据用户描述推断任务标题、是否重要、是否紧急。")
    public String collectTask(
            @ToolParam(description = "任务标题，从用户自然语言中提炼的核心待办内容") String title,
            @ToolParam(description = "是否重要，根据用户描述推断，默认为false", required = false) Boolean isImportant,
            @ToolParam(description = "是否紧急，根据用户描述推断，默认为false", required = false) Boolean isUrgent) {
        TaskItem task = taskPlanService.collectTask(
                title,
                isImportant != null ? isImportant : false,
                isUrgent != null ? isUrgent : false
        );
        return formatTask(task, "任务收纳成功");
    }

    // ==================== 2. 每日计划 ====================

    @Tool(description = "获取所有未完成的任务列表，用于制定每日计划时查看有哪些待办任务可选")
    public String getUncompletedTasks() {
        List<TaskItem> tasks = taskPlanService.getUncompletedTasks();
        if (tasks.isEmpty()) {
            return "当前没有未完成的任务，可以先收纳一些待办事项。";
        }
        StringBuilder sb = new StringBuilder("未完成任务列表（共").append(tasks.size()).append("条）：\n");
        for (int i = 0; i < tasks.size(); i++) {
            TaskItem t = tasks.get(i);
            sb.append(String.format("%d. [ID:%d] %s", i + 1, t.getId(), t.getTitle()));
            if (Boolean.TRUE.equals(t.getIsImportant())) sb.append(" [重要]");
            if (Boolean.TRUE.equals(t.getIsUrgent())) sb.append(" [紧急]");
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "获取近一周的每日计划历史记录，用于制定今日计划时参考过往的执行情况和完成率")
    public String getRecentPlanHistory() {
        List<DailyPlan> plans = taskPlanService.getRecentPlanHistory();
        if (plans.isEmpty()) {
            return "近一周暂无计划记录。";
        }
        StringBuilder sb = new StringBuilder("近一周计划历史（共").append(plans.size()).append("条）：\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (DailyPlan p : plans) {
            String taskTitle = (p.getTask() != null) ? p.getTask().getTitle() : "未知任务";
            sb.append(String.format("- %s: %s [%s]",
                    p.getPlanDate().format(fmt), taskTitle, p.getStatus()));
            if (p.getRemark() != null) {
                sb.append(" 备注: ").append(p.getRemark());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "创建每日计划：将用户确认的任务写入今日计划。每日最多安排3件事。需要传入用户确认的任务ID列表。")
    public String createDailyPlan(
            @ToolParam(description = "用户确认要加入今日计划的任务ID列表，最多3个") List<Long> taskIds) {
        List<DailyPlan> plans = taskPlanService.createDailyPlan(taskIds);
        StringBuilder sb = new StringBuilder("今日计划创建成功，共").append(plans.size()).append("条：\n");
        for (int i = 0; i < plans.size(); i++) {
            DailyPlan p = plans.get(i);
            String taskTitle = (p.getTask() != null) ? p.getTask().getTitle() : "未知任务";
            sb.append(String.format("%d. %s\n", i + 1, taskTitle));
        }
        return sb.toString();
    }

    // ==================== 3. 状态变更 ====================

    @Tool(description = "将指定的每日计划标记为已完成。注意：此操作不可撤销，不支持回退为待完成状态。")
    public String completePlan(
            @ToolParam(description = "每日计划的ID") Long planId) {
        DailyPlan plan = taskPlanService.completePlan(planId);
        String taskTitle = (plan.getTask() != null) ? plan.getTask().getTitle() : "未知任务";
        return String.format("计划[ID:%d] \"%s\" 已标记为完成。对应任务也已同步更新为已完成。", planId, taskTitle);
    }

    @Tool(description = "给指定的每日计划添加或更新备注，例如推迟原因、补充说明等")
    public String addPlanRemark(
            @ToolParam(description = "每日计划的ID") Long planId,
            @ToolParam(description = "备注内容") String remark) {
        DailyPlan plan = taskPlanService.addPlanRemark(planId, remark);
        String taskTitle = (plan.getTask() != null) ? plan.getTask().getTitle() : "未知任务";
        return String.format("计划[ID:%d] \"%s\" 备注已更新: %s", planId, taskTitle, remark);
    }

    // ==================== 4. 任务查询 ====================

    @Tool(description = "查询任务列表。可按状态筛选（UNCOMPLETED=未完成, COMPLETED=已完成），不传状态则返回所有非删除任务。")
    public String queryTasks(
            @ToolParam(description = "任务状态筛选：UNCOMPLETED(未完成) 或 COMPLETED(已完成)，留空则查所有", required = false) String status) {
        List<TaskItem> tasks = taskPlanService.queryTasks(status);
        if (tasks.isEmpty()) {
            return "没有找到符合条件的任务。";
        }
        StringBuilder sb = new StringBuilder("任务列表（共").append(tasks.size()).append("条）：\n");
        for (int i = 0; i < tasks.size(); i++) {
            TaskItem t = tasks.get(i);
            sb.append(String.format("%d. [ID:%d] %s [%s]", i + 1, t.getId(), t.getTitle(), t.getStatus()));
            if (Boolean.TRUE.equals(t.getIsImportant())) sb.append(" [重要]");
            if (Boolean.TRUE.equals(t.getIsUrgent())) sb.append(" [紧急]");
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "查询指定日期的每日计划。可以查看今天或其他日期的计划安排。")
    public String queryDailyPlans(
            @ToolParam(description = "查询日期，格式为yyyy-MM-dd，例如2026-06-23。不传则查今天", required = false) String date) {
        LocalDate queryDate = null;
        if (date != null && !date.isEmpty()) {
            queryDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        List<DailyPlan> plans = taskPlanService.queryDailyPlans(queryDate);
        String dateStr = (queryDate != null) ? queryDate.toString() : LocalDate.now().toString();
        if (plans.isEmpty()) {
            return dateStr + " 暂无计划安排。";
        }
        StringBuilder sb = new StringBuilder(dateStr).append(" 每日计划（共").append(plans.size()).append("条）：\n");
        for (int i = 0; i < plans.size(); i++) {
            DailyPlan p = plans.get(i);
            String taskTitle = (p.getTask() != null) ? p.getTask().getTitle() : "未知任务";
            sb.append(String.format("%d. [ID:%d] %s [%s]", i + 1, p.getId(), taskTitle, p.getStatus()));
            if (p.getRemark() != null) {
                sb.append(" 备注: ").append(p.getRemark());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== 5. 复盘分析 ====================

    @Tool(description = "获取近一周的复盘统计数据，包括总计划数、完成数、完成率等，用于生成复盘报告和优化建议。")
    public String getWeeklyReviewData() {
        Map<String, Object> data = taskPlanService.getWeeklyReviewData();
        int total = (int) data.get("totalPlans");
        if (total == 0) {
            return "近一周暂无计划记录，无法生成复盘报告。建议先收纳一些任务并制定每日计划。";
        }
        int completed = (int) data.get("completedPlans");
        int pending = (int) data.get("pendingPlans");
        String rate = (String) data.get("completionRate");
        LocalDate startDate = (LocalDate) data.get("startDate");
        LocalDate endDate = (LocalDate) data.get("endDate");

        StringBuilder sb = new StringBuilder();
        sb.append("=== 近一周复盘数据 ===\n");
        sb.append(String.format("时间范围: %s ~ %s\n", startDate, endDate));
        sb.append(String.format("计划总数: %d\n", total));
        sb.append(String.format("已完成: %d\n", completed));
        sb.append(String.format("未完成: %d\n", pending));
        sb.append(String.format("完成率: %s\n", rate));

        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    private String formatTask(TaskItem task, String message) {
        return String.format("%s！任务ID: %d，标题: \"%s\"，重要: %s，紧急: %s",
                message, task.getId(), task.getTitle(),
                Boolean.TRUE.equals(task.getIsImportant()) ? "是" : "否",
                Boolean.TRUE.equals(task.getIsUrgent()) ? "是" : "否");
    }
}
