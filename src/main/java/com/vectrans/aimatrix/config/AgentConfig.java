package com.vectrans.aimatrix.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.vectrans.aimatrix.context.ContextWindowHook;
import com.vectrans.aimatrix.context.DynamicContextInterceptor;
import com.vectrans.aimatrix.observability.ObservabilityInterceptor;
import com.vectrans.aimatrix.observability.ObservabilityRecorder;
import com.vectrans.aimatrix.service.AgentMemoryService;
import com.vectrans.aimatrix.tool.TaskTools;
import org.redisson.api.RedissonClient;
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

    /** 短期记忆窗口：单会话保留的最大消息条数 */
    @Value("${agent.memory.window-size:20}")
    private int memoryWindowSize;

    /** 长期记忆召回条数 */
    @Value("${agent.memory.recall-top-k:5}")
    private int memoryRecallTopK;

    /**
     * 运行时模型名：仅用于给观测层兜底（框架请求侧读不到模型名）。
     * 与 ChatModel 复用同一配置项，保证日志中的 model 与实际调用模型一致。
     */
    @Value("${spring.ai.dashscope.chat.options.model:}")
    private String chatModelName;

    private static final String SYSTEM_INSTRUCTION = """
            你是 AIMatrix，一个专业的智能任务规划助手，同时具备通用知识问答能力。请用中文回答用户的问题。

            ## 核心能力
            1. **任务收纳**：用户口语化描述待办时，调用 collectTask 解析并存储任务
            2. **每日计划**：用户要求制定今日计划时，先调用 getUncompletedTasks 和 getRecentPlanHistory 获取数据，
               然后按重要/紧急程度推荐最多3件事（给出时间段建议和预估工时），
               等待用户确认后再调用 createDailyPlan 写入数据库
            3. **状态变更**：用户说某事完成了，调用 completePlan；用户要加备注，调用 addPlanRemark
            4. **任务查询**：根据用户需求调用 queryTasks 或 queryDailyPlans
            5. **复盘分析**：用户要看周复盘时，调用 getWeeklyReviewData 获取数据并生成人性化报告和优化建议
            6. **通用问答**：当用户询问与任务规划无关的普通问题时（如知识问答、概念解释、闲聊等），
               直接用自己的知识回答，不需要调用任何工具
            7. **长期记忆**：当用户表达需要被长期记住的个人偏好、习惯或事实（如"我喜欢早上处理重要任务"）时，
               调用 remember 记录；当需要了解用户过往偏好以更好地服务时，调用 recall 检索

            ## 行为准则
            - 每次回答前先判断用户意图：是任务规划类需求 → 调用对应工具；是通用问题 → 直接回答
            - 用户明确表达"记住"、"以后都"、"我习惯/我喜欢"等长期偏好时，主动调用 remember 存储
            - 制定计划或给出个性化建议前，可先调用 recall 检索用户的长期偏好，使建议更贴合用户习惯
            - 每日计划最多安排3件事，聚焦最重要的任务
            - 每日计划推荐时，优先推荐重要且紧急的任务
            - 标记计划完成时，对应任务会自动同步为已完成，无需额外操作
            - 已完成的任务不可回退为未完成，需要重新收纳创建新任务
            - 复盘报告中，如果完成率低于50%，建议用户减少每日计划数量或评估任务优先级
            """;

    @Bean
    public ReactAgent reactAgent(ChatModel chatModel, TaskTools taskTools,
                                 AgentMemoryService agentMemoryService, RedissonClient redissonClient,
                                 ObservabilityRecorder observabilityRecorder) {
        return ReactAgent.builder()
                .name(agentName)
                .model(chatModel)
                .instruction(SYSTEM_INSTRUCTION)
                .methodTools(taskTools)
                .hooks(ModelCallLimitHook.builder()
                                .runLimit(maxIterations)
                                .build(),
                        new ContextWindowHook(memoryWindowSize, observabilityRecorder))
                // 拦截器链顺序：interceptors[0] 最外层。
                // ObservabilityInterceptor 必须置于最后（最内层），其 handler.call() 才等价于纯模型调用，
                // 避免把长期记忆检索耗时误计入模型耗时。
                .interceptors(new DynamicContextInterceptor(agentMemoryService, memoryRecallTopK, observabilityRecorder),
                        new ObservabilityInterceptor(observabilityRecorder, chatModelName))
                .saver(RedisSaver.builder()
                        .redisson(redissonClient)
                        .build())
                .build();
    }
}
