package com.vectrans.aimatrix.observability;

/**
 * 一次完整对话的可观测事件（请求生命周期内所有模型调用聚合后的结果）。
 * <p>
 * 该对象是「采集层」与「输出层」之间唯一的数据契约：采集层负责填充字段，
 * 输出层（本期为 {@link LoggingSink}）负责渲染。后续接入外部可观测平台时，
 * 只需新增 {@link ObservabilitySink} 实现，采集点无需改动。
 * <p>
 * 高基数字段（sessionId / userId / toolNames）只允许出现在本对象中，
 * 严禁作为 Micrometer 的 metric tag 使用。
 *
 * @param sessionId        会话 ID（高基数，仅作日志字段）
 * @param userId           用户 ID（高基数，仅作日志字段）
 * @param model            本次对话使用的模型名
 * @param promptTokens     输入 token 累计
 * @param completionTokens 输出 token 累计
 * @param totalTokens      总 token 累计
 * @param llmMs            所有模型调用耗时之和（毫秒）
 * @param wallMs           整次请求墙钟耗时（毫秒）
 * @param iterations       实际发生的模型调用轮次
 * @param maxIterations    轮次上限
 * @param trimmedMessages  最近一次模型调用前裁剪掉的消息条数
 * @param originalMessages 最近一次模型调用前的消息条数（未裁剪时即当前上下文规模）
 * @param injectedMemories 本次对话注入的长期记忆条数
 * @param memoryRecall     记忆召回结果：hit（命中）/ miss（已尝试未命中）/ -（未尝试）
 * @param toolNames        本次对话调用过的工具名（逗号分隔，无则为 -）
 * @param toolCalls        工具调用次数
 * @param emptyAnswer      是否产生了空回答
 * @param slow             是否存在超阈值的慢调用
 * @param status           整体状态：OK / ERROR
 * @param traceId          链路追踪占位（本期为空，后续接入平台时填充）
 * @param prompt           用户提示词原文（仅 agent.observability.log-payload=true 时非空）
 * @param reply            助手回复原文（仅 agent.observability.log-payload=true 时非空）
 */
public record LlmCallEvent(
        String sessionId,
        String userId,
        String model,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long llmMs,
        long wallMs,
        int iterations,
        int maxIterations,
        int trimmedMessages,
        int originalMessages,
        int injectedMemories,
        String memoryRecall,
        String toolNames,
        int toolCalls,
        boolean emptyAnswer,
        boolean slow,
        String status,
        String traceId,
        String prompt,
        String reply) {
}
