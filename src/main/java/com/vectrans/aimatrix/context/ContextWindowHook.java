package com.vectrans.aimatrix.context;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文窗口裁剪 Hook（执行于模型调用前）
 * <p>
 * 作为状态级 Hook 挂在 BEFORE_MODEL 节点，将过长的会话消息历史裁剪到最近 windowSize 条，
 * 且裁剪结果会写回图状态，随 Checkpoint 持久化，从而避免历史无限膨胀。
 * <p>
 * 裁剪遵循“不破坏对话结构”原则：优先向后对齐到最近的用户消息边界，使窗口以用户提问开头；
 * 当无法对齐到用户消息边界时，从候选位置起跳过前导的工具响应消息，避免出现无对应请求的孤立工具结果。
 */
@HookPositions(HookPosition.BEFORE_MODEL)
public class ContextWindowHook extends MessagesModelHook {

    /** 默认保留的消息条数 */
    public static final int DEFAULT_WINDOW_SIZE = 20;

    private final int windowSize;

    public ContextWindowHook() {
        this(DEFAULT_WINDOW_SIZE);
    }

    public ContextWindowHook(int windowSize) {
        this.windowSize = windowSize > 0 ? windowSize : DEFAULT_WINDOW_SIZE;
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        return new AgentCommand(applyWindow(previousMessages, windowSize));
    }

    @Override
    public String getName() {
        return "ContextWindow";
    }

    /**
     * 对消息列表执行窗口裁剪（纯函数，便于单元测试）
     *
     * @param messages   原始消息列表
     * @param windowSize 需要保留的最大消息条数
     * @return 裁剪后的新列表；若无需裁剪则返回原列表引用
     */
    public static List<Message> applyWindow(List<Message> messages, int windowSize) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        int effectiveSize = windowSize > 0 ? windowSize : DEFAULT_WINDOW_SIZE;
        if (messages.size() <= effectiveSize) {
            return messages;
        }

        // 第一阶段：优先向后对齐到最近的用户消息边界，保证窗口以完整的一轮对话开头
        int candidate = messages.size() - effectiveSize;
        int alignedStart = candidate;
        while (alignedStart > 0 && !(messages.get(alignedStart) instanceof UserMessage)) {
            alignedStart--;
        }
        if (alignedStart > 0 && messages.get(alignedStart) instanceof UserMessage) {
            return new ArrayList<>(messages.subList(alignedStart, messages.size()));
        }

        // 第二阶段：无法对齐到用户消息边界时，从候选位置起跳过前导的工具响应消息
        int fallbackStart = candidate;
        while (fallbackStart < messages.size() && messages.get(fallbackStart) instanceof ToolResponseMessage) {
            fallbackStart++;
        }
        if (fallbackStart >= messages.size()) {
            return messages;
        }
        return new ArrayList<>(messages.subList(fallbackStart, messages.size()));
    }
}
