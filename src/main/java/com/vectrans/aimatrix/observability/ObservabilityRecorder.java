package com.vectrans.aimatrix.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 可观测聚合器：采集层的统一入口。
 * <p>
 * 两类职责：
 * <ol>
 *   <li><b>会话级累加</b>：以 sessionId 为键聚合一次请求内多次模型调用的 token、耗时、轮次、
 *       工具与上下文处理结果（流式场景跨线程，因此必须靠 ConcurrentHashMap + 原子量，
 *       不能用 ThreadLocal）。</li>
 *   <li><b>指标上报</b>：把 Agent 决策层指标交给 Micrometer 标准 API，
 *       最终通过 {@code /actuator/metrics} 被动拉取。</li>
 * </ol>
 * <p>
 * 硬性约束：
 * <ul>
 *   <li>高基数字段（sessionId / userId / 工具名）严禁作为 metric tag，只允许进入 {@link LlmCallEvent}；</li>
 *   <li>计数器必须使用 Micrometer 标准 API，禁止自造内存 Map 计数器；</li>
 *   <li>所有方法必须异常静默，观测失败不得影响主对话链路。</li>
 * </ul>
 */
@Component
public class ObservabilityRecorder {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityRecorder.class);

    /** Agent 轮次（模型调用次数） */
    static final String METRIC_REACT_ITERATIONS = "agent.react.iterations";

    /** Agent 轮次耗尽 */
    static final String METRIC_REACT_LIMIT_REACHED = "agent.react.limit.reached";

    /** 上下文窗口裁剪掉的消息条数 */
    static final String METRIC_CONTEXT_TRIMMED = "agent.context.trimmed.messages";

    /** 注入的长期记忆条数 */
    static final String METRIC_MEMORY_INJECTED = "agent.memory.injected";

    /** 长期记忆召回次数 */
    static final String METRIC_MEMORY_RECALL = "agent.memory.recall";

    /** 上下文降级次数 */
    static final String METRIC_CONTEXT_DEGRADE = "agent.context.degrade";

    /** 空回答次数 */
    static final String METRIC_ANSWER_EMPTY = "agent.answer.empty";

    static final String TAG_RESULT = "result";
    static final String TAG_CAUSE = "cause";

    static final String RESULT_HIT = "hit";
    static final String RESULT_MISS = "miss";

    /** 上下文降级原因（低基数枚举，仅允许这两类） */
    public static final String CAUSE_MEMORY_INJECTION = "memory_injection";
    public static final String CAUSE_MEMORY_RECALL = "memory_recall";

    static final String STATUS_OK = "OK";
    static final String STATUS_ERROR = "ERROR";
    static final String RECALL_NONE = "-";

    private final boolean enabled;
    private final long slowCallThresholdMs;
    private final int maxIterations;
    private final MeterRegistry meterRegistry;
    private final ObservabilitySink sink;

    /** 会话级累加器：在一次请求的生命周期内存在，finish 时取出并移除 */
    private final ConcurrentHashMap<String, SessionAccumulator> sessions = new ConcurrentHashMap<>();

    public ObservabilityRecorder(@Value("${agent.observability.enabled:true}") boolean enabled,
                                 @Value("${agent.observability.slow-call-threshold-ms:5000}") long slowCallThresholdMs,
                                 @Value("${agent.max-iterations:10}") int maxIterations,
                                 MeterRegistry meterRegistry,
                                 ObservabilitySink sink) {
        this.enabled = enabled;
        this.slowCallThresholdMs = slowCallThresholdMs;
        this.maxIterations = maxIterations > 0 ? maxIterations : 10;
        this.meterRegistry = meterRegistry;
        this.sink = sink;
    }

    /**
     * 构造一个完全关闭的实例：不采集、不打印、不注册指标。
     * <p>
     * 用于单测直接 new 出的组件（如保留旧构造器的 {@code DynamicContextInterceptor}），
     * 使其在不注入 Spring 依赖的前提下保持零副作用。
     */
    public static ObservabilityRecorder noop() {
        return new ObservabilityRecorder(false, 0L, 10, new SimpleMeterRegistry(), event -> {
            // 关闭态下不会产生任何事件
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 开启一次请求的观测：创建会话累加器
     *
     * @param sessionId 会话 ID（必填，缺失时将无法聚合）
     * @param userId    用户 ID（高基数，仅作日志字段）
     */
    public void startSession(String sessionId, String userId) {
        if (!enabled || !hasText(sessionId)) {
            return;
        }
        silently(() -> sessions.put(sessionId, new SessionAccumulator(userId)));
    }

    /**
     * 标记一次模型调用开始：累加轮次，并补全会话上下文中缺失的 userId / model
     */
    public void beginModelCall(String sessionId, String userId, String model) {
        if (!enabled) {
            return;
        }
        silently(() -> {
            meterRegistry.counter(METRIC_REACT_ITERATIONS).increment();
            SessionAccumulator acc = accumulator(sessionId);
            if (acc == null) {
                return;
            }
            acc.iterations.incrementAndGet();
            if (hasText(userId)) {
                acc.userId = userId;
            }
            if (!hasText(acc.model) && hasText(model)) {
                acc.model = model;
            }
        });
    }

    /**
     * 记录一次成功的模型调用
     *
     * @param sessionId        会话 ID
     * @param model            模型名
     * @param promptTokens     输入 token（可空）
     * @param completionTokens 输出 token（可空）
     * @param totalTokens      总 token（可空，缺失时按输入+输出补算）
     * @param toolNames        本次调用涉及的工具名
     * @param elapsedMs        本次调用耗时
     */
    public void recordModelCall(String sessionId, String model, Integer promptTokens, Integer completionTokens,
                                Integer totalTokens, List<String> toolNames, long elapsedMs) {
        if (!enabled) {
            return;
        }
        silently(() -> {
            SessionAccumulator acc = accumulator(sessionId);
            if (acc == null) {
                return;
            }
            long prompt = positive(promptTokens);
            long completion = positive(completionTokens);
            acc.promptTokens.add(prompt);
            acc.completionTokens.add(completion);
            acc.totalTokens.add(positive(totalTokens) > 0 ? positive(totalTokens) : prompt + completion);
            acc.llmMs.add(Math.max(0L, elapsedMs));
            if (!hasText(acc.model) && hasText(model)) {
                acc.model = model;
            }
            if (slowCallThresholdMs > 0 && elapsedMs >= slowCallThresholdMs) {
                acc.slow.set(true);
            }
            if (toolNames != null && !toolNames.isEmpty()) {
                int valid = 0;
                for (String name : toolNames) {
                    if (hasText(name)) {
                        acc.toolNames.add(name.trim());
                        valid++;
                    }
                }
                acc.toolCalls.addAndGet(valid);
            }
        });
    }

    /**
     * 记录一次失败的模型调用（异常会被上层继续抛出，这里只做记录）
     */
    public void recordModelCallFailure(String sessionId, long elapsedMs) {
        if (!enabled) {
            return;
        }
        silently(() -> {
            SessionAccumulator acc = accumulator(sessionId);
            if (acc == null) {
                return;
            }
            acc.llmMs.add(Math.max(0L, elapsedMs));
            acc.error.set(true);
        });
    }

    /**
     * 记录上下文窗口裁剪结果（每次模型调用前都会触发）
     *
     * @param sessionId 会话 ID
     * @param removed   本次裁剪掉的消息条数
     * @param original  裁剪前的消息条数
     */
    public void recordContextTrim(String sessionId, int removed, int original) {
        if (!enabled) {
            return;
        }
        silently(() -> {
            if (removed > 0) {
                meterRegistry.counter(METRIC_CONTEXT_TRIMMED).increment(removed);
            }
            SessionAccumulator acc = accumulator(sessionId);
            if (acc != null) {
                acc.trimmed.set(Math.max(0, removed));
                acc.original.set(Math.max(0, original));
            }
        });
    }

    /**
     * 记录一次长期记忆注入（同一会话内只统计首次，避免多轮 ReAct 重复累加）。
     * <p>
     * 命中时同时把会话的召回结果标记为 {@link #RESULT_HIT}；召回条数由
     * {@link LlmCallEvent#injectedMemories()} 单独承载，二者语义不同，不再拼接为 {@code hit:N}。
     *
     * @param sessionId    会话 ID
     * @param memoryLines  实际注入的记忆条数
     */
    public void recordMemoryInjection(String sessionId, int memoryLines) {
        if (!enabled || memoryLines <= 0) {
            return;
        }
        silently(() -> {
            SessionAccumulator acc = accumulator(sessionId);
            if (acc == null) {
                meterRegistry.counter(METRIC_MEMORY_INJECTED).increment(memoryLines);
                return;
            }
            if (acc.memoryInjected.compareAndSet(false, true)) {
                meterRegistry.counter(METRIC_MEMORY_INJECTED).increment(memoryLines);
                acc.injected.set(memoryLines);
                // memRecall 只表达召回结果（hit / miss / -），条数由 memInject 承载
                acc.memoryRecall = RESULT_HIT;
            }
        });
    }

    /**
     * 标记一次「已尝试召回但未命中」的长期记忆召回。
     * <p>
     * 只更新会话事件字段，用于把日志中的三种状态区分开：
     * 未尝试（{@link #RECALL_NONE}）/ 未命中（{@link #RESULT_MISS}）/ 命中（{@link #RESULT_HIT}）。
     * 指标 {@link #METRIC_MEMORY_RECALL} 由记忆服务在真实检索处上报，此处不重复计数。
     * <p>
     * 命中优先级高于未命中：一旦已记录命中，此调用不再覆盖，保证多轮 ReAct 下结果稳定。
     *
     * @param sessionId 会话 ID
     */
    public void recordMemoryMiss(String sessionId) {
        if (!enabled) {
            return;
        }
        silently(() -> {
            SessionAccumulator acc = accumulator(sessionId);
            if (acc != null && !acc.memoryInjected.get()) {
                acc.memoryRecall = RESULT_MISS;
            }
        });
    }

    /**
     * 记录一次长期记忆召回（由记忆服务在真实检索后调用，避免与注入统计重复计数）
     *
     * @param hitCount 召回条数，0 表示未命中
     */
    public void recordMemoryRecall(int hitCount) {
        if (!enabled) {
            return;
        }
        silently(() -> meterRegistry.counter(METRIC_MEMORY_RECALL,
                TAG_RESULT, hitCount > 0 ? RESULT_HIT : RESULT_MISS).increment());
    }

    /**
     * 记录一次上下文降级
     *
     * @param cause     降级原因（低基数枚举）
     * @param sessionId 会话 ID，可为空
     */
    public void recordContextDegrade(String cause, String sessionId) {
        if (!enabled) {
            return;
        }
        silently(() -> {
            meterRegistry.counter(METRIC_CONTEXT_DEGRADE,
                    TAG_CAUSE, hasText(cause) ? cause : "unknown").increment();
            // 降级只影响指标，不影响会话事件，避免把「已降级」误判为请求失败
            accumulator(sessionId);
        });
    }

    /**
     * 结束一次请求的观测：取出并销毁会话累加器，输出唯一一行事件
     *
     * @param sessionId   会话 ID
     * @param success     本次请求是否成功
     * @param emptyAnswer 是否产生了空回答
     * @param wallMs      整次请求墙钟耗时
     * @param prompt      用户提示词原文（仅用于 log-payload）
     * @param reply       助手回复原文（仅用于 log-payload）
     * @return 已输出的事件；未开启观测或会话不存在时返回 null
     */
    public LlmCallEvent finish(String sessionId, boolean success, boolean emptyAnswer, long wallMs,
                               String prompt, String reply) {
        if (!enabled || !hasText(sessionId)) {
            return null;
        }
        return silentlyGet(() -> {
            SessionAccumulator acc = sessions.remove(sessionId);
            if (acc == null) {
                return null;
            }
            int iterations = acc.iterations.get();
            if (iterations >= maxIterations) {
                // 框架的轮次上限由 ModelCallLimitHook 内部触发，其触发点无法直接观测；
                // 此处以「模型调用轮次达到上限」作为等价信号，用于暴露轮次耗尽风险。
                meterRegistry.counter(METRIC_REACT_LIMIT_REACHED).increment();
            }
            if (emptyAnswer) {
                meterRegistry.counter(METRIC_ANSWER_EMPTY).increment();
            }
            Set<String> names = acc.toolNames;
            String toolNames = names.isEmpty() ? null : String.join(",", names.stream().sorted().toList());
            LlmCallEvent event = new LlmCallEvent(
                    sessionId,
                    acc.userId,
                    acc.model,
                    acc.promptTokens.sum(),
                    acc.completionTokens.sum(),
                    acc.totalTokens.sum(),
                    acc.llmMs.sum(),
                    Math.max(0L, wallMs),
                    iterations,
                    maxIterations,
                    acc.trimmed.get(),
                    acc.original.get(),
                    acc.injected.get(),
                    hasText(acc.memoryRecall) ? acc.memoryRecall : RECALL_NONE,
                    toolNames,
                    acc.toolCalls.get(),
                    emptyAnswer,
                    acc.slow.get(),
                    (!success || acc.error.get()) ? STATUS_ERROR : STATUS_OK,
                    null,
                    prompt,
                    reply);
            emit(event);
            return event;
        });
    }

    private void emit(LlmCallEvent event) {
        try {
            sink.accept(event);
        } catch (Exception e) {
            log.warn("可观测事件输出失败，已忽略：{}", e.getMessage());
        }
    }

    private SessionAccumulator accumulator(String sessionId) {
        return hasText(sessionId) ? sessions.get(sessionId) : null;
    }

    private static long positive(Integer value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 观测逻辑必须异常静默：任何采集失败都不得影响主链路 */
    private void silently(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("可观测采集失败，已忽略：{}", e.getMessage());
        }
    }

    /** 静默执行并返回结果，异常时返回 null */
    private <T> T silentlyGet(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("可观测采集失败，已忽略：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 单次请求的会话级累加器（跨线程使用，字段均为线程安全类型）
     */
    private static final class SessionAccumulator {

        private volatile String userId;
        private volatile String model;
        private final AtomicInteger iterations = new AtomicInteger();
        private final LongAdder promptTokens = new LongAdder();
        private final LongAdder completionTokens = new LongAdder();
        private final LongAdder totalTokens = new LongAdder();
        private final LongAdder llmMs = new LongAdder();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final Set<String> toolNames = ConcurrentHashMap.newKeySet();
        private final AtomicInteger trimmed = new AtomicInteger();
        private final AtomicInteger original = new AtomicInteger();
        private final AtomicInteger injected = new AtomicInteger();
        private final AtomicBoolean memoryInjected = new AtomicBoolean(false);
        private final AtomicBoolean slow = new AtomicBoolean(false);
        private final AtomicBoolean error = new AtomicBoolean(false);
        private volatile String memoryRecall = RECALL_NONE;

        SessionAccumulator(String userId) {
            this.userId = userId;
        }
    }

    /** 供单测与调试使用：当前活跃会话数（正常情况下应恒为 0） */
    int activeSessionCount() {
        return sessions.size();
    }
}
