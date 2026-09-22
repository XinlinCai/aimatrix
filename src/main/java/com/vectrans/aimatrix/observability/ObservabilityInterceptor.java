package com.vectrans.aimatrix.observability;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型调用观测拦截器：采集层与框架之间的唯一挂载点。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>只做「计时 + 读取 token / 工具调用 + 交给 {@link ObservabilityRecorder}」，不做任何聚合与输出；</li>
 *   <li>自身不注册指标、不打印日志，保证与输出通道解耦；</li>
 *   <li>必须放在拦截器链的<b>最内层</b>（最贴近真实模型调用），
 *       这样 {@code handler.call(request)} 才等价于一次真实的 LLM 调用。</li>
 * </ul>
 * <p>
 * 流式 / 非流式两条分支的差异（已核实框架源码 {@code AgentLlmNode}）：
 * <ul>
 *   <li>流式：{@code getMessage()} 为 {@code Flux<ChatResponse>}，{@code getChatResponse() == null}。
 *       此处必须包装该 Flux，在流终止时回填用量；
 *       <b>严禁逐 chunk 累加</b>（DashScope 返回的是累计值），只取<b>最后一个非零 Usage</b>。</li>
 *   <li>非流式：{@code getChatResponse() != null}，直接读用量后<b>原样返回</b>，
 *       以保留下游（框架自身）依赖 {@code getChatResponse()} 取 usage 的能力。</li>
 * </ul>
 */
public class ObservabilityInterceptor extends ModelInterceptor {

    private static final String CONTEXT_KEY_SESSION_ID = "session_id";
    private static final String CONTEXT_KEY_USER_ID = "user_id";

    private final ObservabilityRecorder recorder;

    /**
     * 请求侧读不到模型名时的兜底值，取值来自配置项 {@code spring.ai.dashscope.chat.options.model}。
     * <p>
     * {@code ReactAgent} 只把 {@link org.springframework.ai.chat.model.ChatModel} 交给框架构建请求，
     * 模型名要到 {@code DashScopeChatModel} 内部才与默认选项合并，因此在
     * {@link ModelRequest#getOptions()} 上取不到，需要配置兜底，否则日志中 model 恒为 {@code -}。
     */
    private final String defaultModel;

    /** 不传兜底模型名时退化为纯请求侧取值，保持与旧行为一致 */
    public ObservabilityInterceptor(ObservabilityRecorder recorder) {
        this(recorder, null);
    }

    public ObservabilityInterceptor(ObservabilityRecorder recorder, String defaultModel) {
        this.recorder = recorder;
        this.defaultModel = hasText(defaultModel) ? defaultModel.trim() : null;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        if (recorder == null || !recorder.isEnabled()) {
            return handler.call(request);
        }
        Map<String, Object> context = request == null ? null : request.getContext();
        String sessionId = resolve(context, CONTEXT_KEY_SESSION_ID);
        String userId = resolve(context, CONTEXT_KEY_USER_ID);
        String model = resolveModel(request);

        long startNanos = System.nanoTime();
        recorder.beginModelCall(sessionId, userId, model);

        ModelResponse response;
        try {
            response = handler.call(request);
        } catch (RuntimeException e) {
            recorder.recordModelCallFailure(sessionId, elapsedMs(startNanos));
            throw e;
        }

        Object message = response == null ? null : response.getMessage();

        // 流式分支：包装 Flux，在流终止时回填用量（本方法立即返回，实际采集发生在订阅后）
        if (message instanceof Flux<?> flux) {
            return ModelResponse.of(wrapStream(sessionId, model, startNanos, asChatResponseFlux(flux)));
        }

        // 非流式分支：读用量后原样返回，避免破坏下游对 getChatResponse() 的依赖
        recordNonStreaming(sessionId, model, startNanos, response, message);
        return response;
    }

    @Override
    public String getName() {
        return "Observability";
    }

    /**
     * 包装流式响应：只在流终止时回填一次用量，保证与「一次模型调用」一一对应
     */
    private Flux<ChatResponse> wrapStream(String sessionId, String model, long startNanos, Flux<ChatResponse> flux) {
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        Map<String, String> toolNamesById = new ConcurrentHashMap<>();
        AtomicBoolean failed = new AtomicBoolean(false);

        return flux.doOnNext(response -> {
                    Usage usage = usageOf(response);
                    if (hasPositiveUsage(usage)) {
                        // 只保留最后一个非零用量：流式返回的是累计值，逐 chunk 累加会严重放大
                        lastUsage.set(usage);
                    }
                    collectToolCalls(outputOf(response), toolNamesById);
                })
                .doOnError(error -> failed.set(true))
                .doFinally(signal -> {
                    long elapsed = elapsedMs(startNanos);
                    if (failed.get()) {
                        recorder.recordModelCallFailure(sessionId, elapsed);
                        return;
                    }
                    Usage usage = lastUsage.get();
                    recorder.recordModelCall(sessionId, model,
                            usage == null ? null : usage.getPromptTokens(),
                            usage == null ? null : usage.getCompletionTokens(),
                            usage == null ? null : usage.getTotalTokens(),
                            new ArrayList<>(toolNamesById.values()),
                            elapsed);
                });
    }

    private void recordNonStreaming(String sessionId, String model, long startNanos,
                                    ModelResponse response, Object message) {
        ChatResponse chatResponse = response == null ? null : response.getChatResponse();
        Usage usage = usageOf(chatResponse);
        List<String> toolNames = message instanceof AssistantMessage assistant
                ? toolNamesOf(assistant)
                : List.of();
        recorder.recordModelCall(sessionId, model,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens(),
                toolNames,
                elapsedMs(startNanos));
    }

    /** 收集流式 chunk 中的工具调用，按调用 id 去重（同一次调用的增量分片会重复出现） */
    private static void collectToolCalls(AssistantMessage output, Map<String, String> toolNamesById) {
        if (output == null || !output.hasToolCalls()) {
            return;
        }
        for (AssistantMessage.ToolCall call : output.getToolCalls()) {
            if (call == null || !hasText(call.name())) {
                continue;
            }
            String key = hasText(call.id()) ? call.id() : call.name();
            toolNamesById.putIfAbsent(key, call.name().trim());
        }
    }

    private static List<String> toolNamesOf(AssistantMessage assistant) {
        Map<String, String> namesById = new ConcurrentHashMap<>();
        collectToolCalls(assistant, namesById);
        return new ArrayList<>(namesById.values());
    }

    private static Usage usageOf(ChatResponse response) {
        return response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
    }

    private static AssistantMessage outputOf(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        return response.getResult().getOutput();
    }

    /** 只有真正取到正数用量时才算有效，用于跳过流式首尾的空 usage 分片 */
    private static boolean hasPositiveUsage(Usage usage) {
        return usage != null
                && (positive(usage.getPromptTokens()) > 0
                || positive(usage.getCompletionTokens()) > 0
                || positive(usage.getTotalTokens()) > 0);
    }

    /**
     * 解析本次调用使用的模型名：优先取请求侧显式配置，缺失时回退到配置项兜底值
     */
    private String resolveModel(ModelRequest request) {
        String model = request == null || request.getOptions() == null ? null : request.getOptions().getModel();
        // hasText 无法向 JDT 空分析传递「实参非空」语义，requireNonNull 显式收敛后才可安全解引用
        return hasText(model) ? Objects.requireNonNull(model).trim() : defaultModel;
    }

    private static String resolve(Map<String, Object> context, String key) {
        if (context == null) {
            return null;
        }
        Object value = context.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static Flux<ChatResponse> asChatResponseFlux(Flux<?> flux) {
        return (Flux<ChatResponse>) flux;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static long positive(Integer value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
