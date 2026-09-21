package com.vectrans.aimatrix.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextWindowHook 窗口裁剪逻辑纯单元测试
 * <p>
 * 不启动 Spring 容器，直接验证静态裁剪算法对边界场景的处理。
 */
@SuppressWarnings("null")
class ContextWindowHookTest {

    @Test
    @DisplayName("消息列表为 null 时原样返回 null")
    void applyWindowShouldReturnNullWhenMessagesNull() {
        System.out.println("-- 用例1：输入 null --");
        List<Message> result = ContextWindowHook.applyWindow(null, 5);

        assertNull(result, "输入为 null 时应返回 null");
        System.out.println("结果：null 已原样返回");
    }

    @Test
    @DisplayName("消息数未超过窗口大小时返回原列表引用")
    void applyWindowShouldReturnSameReferenceWhenWithinWindow() {
        System.out.println("-- 用例2：未超过窗口大小 --");
        List<Message> messages = List.of(new UserMessage("问题1"), new AssistantMessage("回答1"), new UserMessage("问题2"));

        List<Message> result = ContextWindowHook.applyWindow(messages, 5);

        assertSame(messages, result, "未超过窗口大小时应返回原列表引用，不做多余拷贝");
        System.out.println("结果：消息数=" + result.size() + "，返回同一引用");
    }

    @Test
    @DisplayName("超窗口时向后对齐到最近的用户消息边界")
    void applyWindowShouldAlignToUserMessageBoundary() {
        System.out.println("-- 用例3：对齐用户消息边界 --");
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("问题1"),
                new AssistantMessage("回答1"),
                new UserMessage("问题2"),
                new AssistantMessage("回答2"),
                new UserMessage("问题3"),
                new AssistantMessage("回答3")));

        List<Message> result = ContextWindowHook.applyWindow(messages, 3);

        // candidate = 6 - 3 = 3（Assistant），向前对齐到 index 2（UserMessage）
        assertEquals(4, result.size(), "应保留从用户消息边界起的 4 条消息");
        assertInstanceOf(UserMessage.class, result.get(0), "裁剪后窗口应以用户消息开头");
        assertEquals("问题2", ((UserMessage) result.get(0)).getText(), "窗口起始应为问题2对应的用户消息");
        System.out.println("结果：窗口起始为「" + ((UserMessage) result.get(0)).getText() + "」，共 " + result.size() + " 条");
    }

    @Test
    @DisplayName("无法对齐用户消息边界时跳过前导的工具响应消息")
    void applyWindowShouldSkipLeadingToolResponsesOnFallback() {
        System.out.println("-- 用例4：回退分支跳过孤儿工具结果 --");
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("帮我规划"),
                new AssistantMessage("调用工具"),
                buildToolResponse("call-1"),
                buildToolResponse("call-2"),
                new AssistantMessage("规划结果"),
                buildToolResponse("call-3")));

        List<Message> result = ContextWindowHook.applyWindow(messages, 4);

        // candidate = 6 - 4 = 2（ToolResponse），向后找不到 UserMessage 边界 → 回退并跳过前导 ToolResponse
        assertEquals(2, result.size(), "回退分支应保留 2 条消息");
        assertInstanceOf(AssistantMessage.class, result.get(0), "回退后窗口不应以孤立工具结果开头");
        System.out.println("结果：窗口起始类型=" + result.get(0).getClass().getSimpleName() + "，共 " + result.size() + " 条");
    }

    @Test
    @DisplayName("无可用裁剪边界时返回原列表")
    void applyWindowShouldReturnOriginalWhenNoValidBoundary() {
        System.out.println("-- 用例5：无可用裁剪边界 --");
        List<Message> messages = new ArrayList<>(List.of(
                buildToolResponse("call-1"),
                buildToolResponse("call-2"),
                buildToolResponse("call-3"),
                buildToolResponse("call-4"),
                buildToolResponse("call-5"),
                buildToolResponse("call-6")));

        List<Message> result = ContextWindowHook.applyWindow(messages, 3);

        assertSame(messages, result, "全部为工具结果时无法安全裁剪，应返回原列表");
        assertTrue(result.size() == 6, "原列表条数应保持不变");
        System.out.println("结果：无可裁剪边界，返回原列表，共 " + result.size() + " 条");
    }

    private ToolResponseMessage buildToolResponse(String id) {
        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(id, "queryTasks", "工具返回内容");
        return ToolResponseMessage.builder().responses(List.of(response)).build();
    }
}
