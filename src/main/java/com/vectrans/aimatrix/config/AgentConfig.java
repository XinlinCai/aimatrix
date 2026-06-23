package com.vectrans.aimatrix.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.vectrans.aimatrix.tool.TaskTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Value("${agent.name:aimatrix-agent}")
    private String agentName;

    @Value("${agent.max-iterations:10}")
    private int maxIterations;

    private static final String SYSTEM_INSTRUCTION = """
            你是 AIMatrix，一个专业的智能任务规划助手。请用中文回答用户的问题。

            ## 核心能力
            1. **任务收纳**：用户口语化描述待办时，调用 collectTask 解析并存储任务
            2. **每日计划**：用户要求制定今日计划时，先调用 getUncompletedTasks 和 getRecentPlanHistory 获取数据，
               然后按重要/紧急程度推荐最多3件事（给出时间段建议和预估工时），
               等待用户确认后再调用 createDailyPlan 写入数据库
            3. **状态变更**：用户说某事完成了，调用 completePlan；用户要加备注，调用 addPlanRemark
            4. **任务查询**：根据用户需求调用 queryTasks 或 queryDailyPlans
            5. **复盘分析**：用户要看周复盘时，调用 getWeeklyReviewData 获取数据并生成人性化报告和优化建议

            ## 业务规则
            - 每日计划最多安排3件事，聚焦最重要的任务
            - 每日计划推荐时，优先推荐重要且紧急的任务
            - 标记计划完成时，对应任务会自动同步为已完成，无需额外操作
            - 已完成的任务不可回退为未完成，需要重新收纳创建新任务
            - 复盘报告中，如果完成率低于50%，建议用户减少每日计划数量或评估任务优先级
            """;

    @Bean
    public ReactAgent reactAgent(ChatModel chatModel, TaskTools taskTools) {
        return ReactAgent.builder()
                .name(agentName)
                .model(chatModel)
                .instruction(SYSTEM_INSTRUCTION)
                .methodTools(taskTools)
                .hooks(ModelCallLimitHook.builder()
                        .runLimit(maxIterations)
                        .build())
                .saver(new MemorySaver())
                .build();
    }
}
