package com.vectrans.aimatrix.service.impl;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.vectrans.aimatrix.dto.AgentRequest;
import com.vectrans.aimatrix.dto.AgentResponse;
import com.vectrans.aimatrix.observability.ObservabilityRecorder;
import com.vectrans.aimatrix.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    /** 单用户开发阶段固定 userId，后续对接认证体系后从请求中获取 */
    private static final String CURRENT_USER_ID = "1";

    private final ReactAgent reactAgent;
    private final ObservabilityRecorder observabilityRecorder;

    public AgentServiceImpl(ReactAgent reactAgent, ObservabilityRecorder observabilityRecorder) {
        this.reactAgent = reactAgent;
        this.observabilityRecorder = observabilityRecorder;
    }

    @Override
    public Flux<String> streamChat(AgentRequest request) {
        if (!StringUtils.hasText(request.getMessage())) {
            return Flux.error(new IllegalArgumentException("消息内容不能为空"));
        }
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId()
                : UUID.randomUUID().toString();
        String message = request.getMessage();
        // 日志脱敏：仅记录长度；原文只在 agent.observability.log-payload=true 时由 [OBS] 单行事件输出
        log.info("Agent streamChat - sessionId: {}, messageLength: {}", sessionId, message.length());

        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .addMetadata("user_id", CURRENT_USER_ID)
                // session_id 透传至 ModelRequest.getContext()，供最内层 ObservabilityInterceptor 关联会话累加器
                .addMetadata("session_id", sessionId)
                .build();

        long startNanos = System.nanoTime();
        AtomicReference<String> replyRef = new AtomicReference<>("");

        return Flux.defer(() -> {
            // 冷流：订阅时才创建会话累加器，避免未订阅请求残留脏数据
            observabilityRecorder.startSession(sessionId, CURRENT_USER_ID);
            try {
                return reactAgent.stream(message, config);
            } catch (Exception e) {
                return Flux.<com.alibaba.cloud.ai.graph.NodeOutput>error(e);
            }
        })
                .filter(nodeOutput -> nodeOutput.isEND())
                .map(nodeOutput -> {
                    List<?> messages = nodeOutput.state().value("messages", List.class).orElse(List.of());
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        if (messages.get(i) instanceof AssistantMessage msg) {
                            return msg.getText() != null ? msg.getText() : "";
                        }
                    }
                    return "";
                })
                .doOnNext(replyRef::set)
                .doOnError(e -> log.error("Agent stream failed - sessionId: {}", sessionId, e))
                // 内层模型 Flux 的 doFinally 必然早于外层，故此处读取累加数据无竞态
                .doFinally(signal -> observabilityRecorder.finish(sessionId,
                        signal != SignalType.ON_ERROR,
                        !StringUtils.hasText(replyRef.get()),
                        elapsedMs(startNanos), message, replyRef.get()));
    }

    @Override
    public AgentResponse chat(AgentRequest request) {
        if (!StringUtils.hasText(request.getMessage())) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId()
                : UUID.randomUUID().toString();
        String message = request.getMessage();
        log.info("Agent chat - sessionId: {}, messageLength: {}", sessionId, message.length());

        observabilityRecorder.startSession(sessionId, CURRENT_USER_ID);
        long startNanos = System.nanoTime();
        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId)
                    .addMetadata("user_id", CURRENT_USER_ID)
                    .addMetadata("session_id", sessionId)
                    .build();
            AssistantMessage response = reactAgent.call(message, config);
            String reply = (response != null && response.getText() != null) ? response.getText() : "";
            // 回复原文改由 [OBS] 单行事件输出（含 token/耗时/轮次等聚合信息）
            observabilityRecorder.finish(sessionId, true, !StringUtils.hasText(reply),
                    elapsedMs(startNanos), message, reply);
            return new AgentResponse(reply, sessionId);
        } catch (Exception e) {
            log.error("Agent call failed - sessionId: {}", sessionId, e);
            observabilityRecorder.finish(sessionId, false, false, elapsedMs(startNanos), message, null);
            throw new RuntimeException("AI 助手暂时无法响应，请稍后重试", e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
