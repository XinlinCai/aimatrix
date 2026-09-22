package com.vectrans.aimatrix.context;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.vectrans.aimatrix.observability.LlmCallEvent;
import com.vectrans.aimatrix.observability.ObservabilityRecorder;
import com.vectrans.aimatrix.service.AgentMemoryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DynamicContextInterceptor 动态上下文注入纯单元测试
 * <p>
 * 不启动 Spring 容器，通过桩实现 AgentMemoryService 并捕获下游请求入参进行断言。
 */
@SuppressWarnings("null")
class DynamicContextInterceptorTest {

    @Test
    @DisplayName("注入运行时日期与长期记忆到系统消息")
    void interceptModelShouldInjectDateAndMemory() {
        System.out.println("-- 用例1：正常注入日期与长期记忆 --");
        AgentMemoryService stubMemory = new StubMemoryService("- 用户偏好早上处理重要任务\n");
        DynamicContextInterceptor interceptor = new DynamicContextInterceptor(stubMemory, 5);
        ModelRequest request = buildRequest("今天帮我安排计划");

        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        interceptor.interceptModel(request, enriched -> {
            captured.set(enriched);
            return null;
        });

        ModelRequest enriched = captured.get();
        assertNotNull(enriched, "下游处理器应被调用");
        SystemMessage systemMessage = enriched.getSystemMessage();
        assertNotNull(systemMessage, "应注入系统消息");
        assertTrue(systemMessage.getText().contains("当前日期：" + LocalDate.now()), "系统消息应包含当前日期");
        assertTrue(systemMessage.getText().contains("用户偏好早上处理重要任务"), "系统消息应包含召回的长期记忆");
        System.out.println("结果：系统消息内容=\n" + systemMessage.getText());
    }

    @Test
    @DisplayName("记忆服务异常时降级为原始请求")
    void interceptModelShouldFallbackWhenMemoryServiceFails() {
        System.out.println("-- 用例2：记忆服务异常降级 --");
        AgentMemoryService failingMemory = new AgentMemoryService() {
            @Override
            public void remember(String userId, String content) {
            }

            @Override
            public List<String> recall(String userId, String query, int topK) {
                return List.of();
            }

            @Override
            public String buildMemoryContext(String userId, String query, int topK) {
                throw new IllegalStateException("向量库不可用");
            }
        };
        DynamicContextInterceptor interceptor = new DynamicContextInterceptor(failingMemory, 5);
        ModelRequest request = buildRequest("今天帮我安排计划");

        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        interceptor.interceptModel(request, enriched -> {
            captured.set(enriched);
            return null;
        });

        ModelRequest enriched = captured.get();
        assertNotNull(enriched, "异常降级后下游处理器仍应被调用");
        SystemMessage systemMessage = enriched.getSystemMessage();
        assertNotNull(systemMessage, "降级时应保留原始系统消息");
        assertEquals("原始系统提示", systemMessage.getText(), "降级后系统消息应保持原始内容不变");
        assertFalse(systemMessage.getText().contains("当前日期"), "降级后不应注入运行时上下文");
        System.out.println("结果：已降级为原始请求，系统消息内容=\"" + systemMessage.getText() + "\"");
    }

    @Test
    @DisplayName("召回结果三态：hit / miss / - 可区分")
    void shouldDistinguishRecallResultTriState() {
        System.out.println("-- 用例3：召回结果三态 --");
        List<LlmCallEvent> events = new ArrayList<>();
        ObservabilityRecorder recorder =
                new ObservabilityRecorder(true, 5000L, 10, new SimpleMeterRegistry(), events::add);

        // hit：发起召回且返回内容（观测埋点需带 session_id 才能落到会话累加器）
        recorder.startSession("s-hit", "1");
        new DynamicContextInterceptor(new StubMemoryService("- 用户偏好早上处理重要任务\n"), 5, recorder)
                .interceptModel(buildRequest("s-hit", "今天帮我安排计划"), enriched -> null);
        recorder.finish("s-hit", true, false, 10L, null, null);

        // miss：发起召回但无可用内容
        recorder.startSession("s-miss", "1");
        new DynamicContextInterceptor(new StubMemoryService(""), 5, recorder)
                .interceptModel(buildRequest("s-miss", "今天帮我安排计划"), enriched -> null);
        recorder.finish("s-miss", true, false, 10L, null, null);

        // -：缺 userId，压根未发起召回，不应误记为 miss
        recorder.startSession("s-none", "1");
        new DynamicContextInterceptor(new StubMemoryService("- 不会被调用的记忆\n"), 5, recorder)
                .interceptModel(buildRequestWithoutUserId("今天帮我安排计划"), enriched -> null);
        recorder.finish("s-none", true, false, 10L, null, null);

        assertEquals(3, events.size(), "三个会话各应产出一行事件");
        assertEquals("hit", events.get(0).memoryRecall(), "命中时应为 hit");
        assertEquals(1, events.get(0).injectedMemories(), "命中时条数由 memInject 承载");
        assertEquals("miss", events.get(1).memoryRecall(), "发起召回但无内容时应为 miss");
        assertEquals(0, events.get(1).injectedMemories(), "未命中时不应有注入条数");
        assertEquals("-", events.get(2).memoryRecall(), "未发起召回时应保持 -");
        System.out.println("结果：hit / miss / - 三态已区分");
    }

    private ModelRequest buildRequest(String userText) {
        return buildRequest(null, userText);
    }

    /** 指定 session_id 的请求：观测埋点需以此落到会话累加器上，否则事件字段保持默认值 */
    private ModelRequest buildRequest(String sessionId, String userText) {
        List<Message> messages = List.of(new UserMessage(userText));
        Map<String, Object> context = new HashMap<>();
        if (sessionId != null) {
            context.put("session_id", sessionId);
        }
        context.put("user_id", "1");
        return ModelRequest.builder()
                .messages(messages)
                .systemMessage(new SystemMessage("原始系统提示"))
                .context(context)
                .build();
    }

    /** 只改上下文键：缺少 user_id 时不应发起召回 */
    private ModelRequest buildRequestWithoutUserId(String userText) {
        List<Message> messages = List.of(new UserMessage(userText));
        return ModelRequest.builder()
                .messages(messages)
                .systemMessage(new SystemMessage("原始系统提示"))
                .context(Map.of("session_id", "s-none"))
                .build();
    }

    /**
     * 桩实现：返回预设的长期记忆上下文
     */
    private static class StubMemoryService implements AgentMemoryService {

        private final String memoryContext;

        StubMemoryService(String memoryContext) {
            this.memoryContext = memoryContext;
        }

        @Override
        public void remember(String userId, String content) {
        }

        @Override
        public List<String> recall(String userId, String query, int topK) {
            return List.of(memoryContext.trim());
        }

        @Override
        public String buildMemoryContext(String userId, String query, int topK) {
            return memoryContext;
        }
    }
}
