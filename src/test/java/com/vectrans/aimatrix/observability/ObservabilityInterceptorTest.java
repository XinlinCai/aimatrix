package com.vectrans.aimatrix.observability;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ObservabilityInterceptor 纯单元测试
 * <p>
 * 不启动 Spring 容器，通过桩 {@code ModelCallHandler} 直接驱动拦截器，验证：
 * 非流式 / 流式两条分支的 token 采集、模型名来源、工具调用按 id 去重、
 * 失败标记，以及与会话累加器的协同（生命周期与轮次累加）。
 */
@SuppressWarnings("null")
class ObservabilityInterceptorTest {

    private static final String SESSION_ID = "obs-test-session";
    private static final String USER_ID = "1";
    private static final String MODEL = "qwen3.7-max";

    private final List<LlmCallEvent> events = new ArrayList<>();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObservabilityRecorder recorder =
            new ObservabilityRecorder(true, 5000L, 10, meterRegistry, events::add);
    private final ObservabilityInterceptor interceptor = new ObservabilityInterceptor(recorder);

    @Test
    @DisplayName("非流式：采集 token 与模型名，并原样返回聊天响应")
    void nonStreamingShouldCaptureTokensAndReturnResponse() {
        System.out.println("-- 用例1：非流式正常采集 --");
        recorder.startSession(SESSION_ID, USER_ID);

        ModelResponse response = interceptor.interceptModel(buildRequest(),
                request -> ModelResponse.of(new AssistantMessage("你好"), chatResponse("你好", 100, 50, 150)));

        assertNotNull(response, "应返回模型响应");
        assertEquals("你好", ((AssistantMessage) response.getMessage()).getText(), "应返回原始助手消息");
        assertEquals(150, response.getChatResponse().getMetadata().getUsage().getTotalTokens(),
                "非流式应保留 getChatResponse()，供下游读取用量");

        LlmCallEvent event = finish(true, false, "你好");
        assertEquals(1, event.iterations(), "应记录 1 个模型调用轮次");
        assertEquals(MODEL, event.model(), "模型名应来自 request.getOptions().getModel()");
        assertEquals(100L, event.promptTokens());
        assertEquals(50L, event.completionTokens());
        assertEquals(150L, event.totalTokens());
        assertEquals(0, event.toolCalls(), "无工具调用时应为 0");
        assertNull(event.toolNames(), "无工具调用时工具名应为空");
        System.out.println("结果：iterations=1, total=" + event.totalTokens() + ", model=" + event.model());
    }

    @Test
    @DisplayName("非流式：采集本轮调用的工具名")
    void nonStreamingShouldCaptureToolCalls() {
        System.out.println("-- 用例2：非流式工具调用采集 --");
        recorder.startSession(SESSION_ID, USER_ID);

        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "queryTasks", "{}")))
                .build();

        interceptor.interceptModel(buildRequest(),
                request -> ModelResponse.of(assistant, chatResponse(assistant, 80, 10, 90)));

        LlmCallEvent event = finish(true, false, "");
        assertEquals(1, event.toolCalls(), "应记录 1 次工具调用");
        assertEquals("queryTasks", event.toolNames(), "应记录工具名");
        System.out.println("结果：toolCalls=" + event.toolCalls() + ", toolNames=" + event.toolNames());
    }

    @Test
    @DisplayName("流式：取最后一个非零累计 usage，而非逐 chunk 累加")
    void streamingShouldKeepLastNonZeroUsage() {
        System.out.println("-- 用例3：流式累计 usage 取值 --");
        recorder.startSession(SESSION_ID, USER_ID);

        ModelResponse response = interceptor.interceptModel(buildRequest(), request -> ModelResponse.of(Flux.just(
                chatResponse(new AssistantMessage("如"), 0, 0, 0),
                chatResponse(new AssistantMessage("如"), 100, 20, 120),
                chatResponse(new AssistantMessage("如如"), 100, 50, 150))));

        Flux<ChatResponse> stream = asFlux(response);
        assertEquals(3, stream.collectList().block().size(), "应原样透传所有 chunk");

        LlmCallEvent event = finish(true, false, "如如");
        assertEquals(150L, event.totalTokens(), "应取最后一个累计 usage（150），逐 chunk 累加会得 270");
        assertEquals(100L, event.promptTokens());
        assertEquals(50L, event.completionTokens());
        System.out.println("结果：total=" + event.totalTokens() + "（首帧 0 被跳过，取末帧累计值）");
    }

    @Test
    @DisplayName("流式：同一次工具调用的增量分片按 id 去重")
    void streamingShouldDeduplicateToolCallsById() {
        System.out.println("-- 用例4：流式工具调用去重 --");
        recorder.startSession(SESSION_ID, USER_ID);

        AssistantMessage first = toolCallMessage("call-1", "queryTasks", "{\"a\"");
        AssistantMessage second = toolCallMessage("call-1", "queryTasks", "{\"a\":1}");

        ModelResponse response = interceptor.interceptModel(buildRequest(), request -> ModelResponse.of(Flux.just(
                chatResponse(first, 0, 0, 0),
                chatResponse(second, 50, 5, 55))));
        asFlux(response).collectList().block();

        LlmCallEvent event = finish(true, false, "");
        assertEquals(1, event.toolCalls(), "同一 id 的增量分片只应计一次");
        assertEquals("queryTasks", event.toolNames());
        System.out.println("结果：toolCalls=" + event.toolCalls() + ", toolNames=" + event.toolNames());
    }

    @Test
    @DisplayName("流式：流异常时标记为失败")
    void streamingShouldMarkFailureWhenStreamErrors() {
        System.out.println("-- 用例5：流式异常标记 --");
        recorder.startSession(SESSION_ID, USER_ID);

        ModelResponse response = interceptor.interceptModel(buildRequest(),
                request -> ModelResponse.of(Flux.error(new RuntimeException("模型不可用"))));

        Flux<ChatResponse> stream = asFlux(response);
        assertThrows(RuntimeException.class, stream::blockLast, "异常应向下游传播");

        // 即便调用方标记整体成功，累加器中的失败标志也应把状态收敛为 ERROR
        LlmCallEvent event = finish(true, false, null);
        assertEquals(ObservabilityRecorder.STATUS_ERROR, event.status(), "流异常应记录为失败状态");
        System.out.println("结果：status=" + event.status());
    }

    @Test
    @DisplayName("多轮 ReAct：轮次与 token 正确累加")
    void multipleCallsShouldAccumulateIterationsAndTokens() {
        System.out.println("-- 用例6：多轮累加 --");
        recorder.startSession(SESSION_ID, USER_ID);

        interceptor.interceptModel(buildRequest(),
                request -> ModelResponse.of(new AssistantMessage("思考"), chatResponse("思考", 100, 20, 120)));
        interceptor.interceptModel(buildRequest(),
                request -> ModelResponse.of(new AssistantMessage("回答"), chatResponse("回答", 120, 30, 150)));

        LlmCallEvent event = finish(true, false, "回答");
        assertEquals(2, event.iterations(), "两次模型调用应累加为 2 轮");
        assertEquals(220L, event.promptTokens(), "输入 token 应累加");
        assertEquals(50L, event.completionTokens(), "输出 token 应累加");
        assertEquals(270L, event.totalTokens(), "总 token 应累加");
        System.out.println("结果：iterations=" + event.iterations() + ", total=" + event.totalTokens());
    }

    @Test
    @DisplayName("观测关闭时直接透传，不产生任何事件与活跃会话")
    void disabledRecorderShouldPassThroughWithoutSideEffects() {
        System.out.println("-- 用例7：观测关闭时零副作用 --");
        List<LlmCallEvent> captured = new ArrayList<>();
        ObservabilityRecorder off = new ObservabilityRecorder(false, 5000L, 10, meterRegistry, captured::add);
        ObservabilityInterceptor offInterceptor = new ObservabilityInterceptor(off);

        ModelResponse expected = ModelResponse.of(new AssistantMessage("原样"));
        ModelResponse actual = offInterceptor.interceptModel(buildRequest(), request -> expected);

        assertSame(expected, actual, "关闭观测时应原样返回处理器结果");
        off.startSession(SESSION_ID, USER_ID);
        assertNull(off.finish(SESSION_ID, true, false, 10L, null, null), "关闭观测时不应产出事件");
        assertEquals(0, off.activeSessionCount(), "关闭观测时不应创建会话累加器");
        assertTrue(captured.isEmpty(), "关闭观测时不应产生任何事件");
        System.out.println("结果：透传且无副作用");
    }

    @Test
    @DisplayName("finish 后会话累加器必须被移除")
    void finishShouldRemoveSessionAccumulator() {
        System.out.println("-- 用例8：累加器生命周期 --");
        recorder.startSession(SESSION_ID, USER_ID);
        assertEquals(1, recorder.activeSessionCount(), "startSession 后应存在 1 个活跃会话");

        interceptor.interceptModel(buildRequest(),
                request -> ModelResponse.of(new AssistantMessage("你好"), chatResponse("你好", 10, 5, 15)));

        finish(true, false, "你好");
        assertEquals(0, recorder.activeSessionCount(), "finish 后累加器必须被移除，避免内存泄漏");
        System.out.println("结果：活跃会话数=" + recorder.activeSessionCount());
    }

    @Test
    @DisplayName("请求侧无模型名时回退到配置兜底值，model 不再为 -")
    void shouldFallbackToConfiguredModelWhenRequestHasNoModel() {
        System.out.println("-- 用例9：model 配置兜底 --");
        ObservabilityInterceptor fallbackInterceptor = new ObservabilityInterceptor(recorder, MODEL);
        recorder.startSession(SESSION_ID, USER_ID);

        fallbackInterceptor.interceptModel(buildRequestWithoutModel(),
                request -> ModelResponse.of(new AssistantMessage("你好"), chatResponse("你好", 10, 5, 15)));

        LlmCallEvent event = finish(true, false, "你好");
        assertEquals(MODEL, event.model(), "请求侧取不到模型名时应回退到配置兜底值");
        System.out.println("结果：model=" + event.model() + "（请求侧未提供模型名）");
    }

    private ModelRequest buildRequest() {
        return ModelRequest.builder()
                .messages(List.of(new UserMessage("你好")))
                .systemMessage(new SystemMessage("系统提示"))
                .options(DefaultToolCallingChatOptions.builder().model(MODEL).build())
                .context(Map.of("session_id", SESSION_ID, "user_id", USER_ID))
                .build();
    }

    /** 模拟 ReactAgent 的实际行为：只传 ChatModel，未显式提供 ChatOptions，因此 options 侧无模型名 */
    private ModelRequest buildRequestWithoutModel() {
        return ModelRequest.builder()
                .messages(List.of(new UserMessage("你好")))
                .systemMessage(new SystemMessage("系统提示"))
                .options(DefaultToolCallingChatOptions.builder().build())
                .context(Map.of("session_id", SESSION_ID, "user_id", USER_ID))
                .build();
    }

    private LlmCallEvent finish(boolean success, boolean emptyAnswer, String reply) {
        return recorder.finish(SESSION_ID, success, emptyAnswer, 120L, "你好", reply);
    }

    private static AssistantMessage toolCallMessage(String id, String name, String arguments) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build();
    }

    private static ChatResponse chatResponse(String content, int prompt, int completion, int total) {
        return chatResponse(new AssistantMessage(content), prompt, completion, total);
    }

    private static ChatResponse chatResponse(AssistantMessage message, int prompt, int completion, int total) {
        Usage usage = new DefaultUsage(prompt, completion, total);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id("resp-obs-test")
                .usage(usage)
                .build();
        return new ChatResponse(List.of(new Generation(message)), metadata);
    }

    @SuppressWarnings("unchecked")
    private static Flux<ChatResponse> asFlux(ModelResponse response) {
        return (Flux<ChatResponse>) response.getMessage();
    }
}
