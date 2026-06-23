package com.vectrans.aimatrix.service.impl;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.vectrans.aimatrix.dto.AgentRequest;
import com.vectrans.aimatrix.dto.AgentResponse;
import com.vectrans.aimatrix.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    /** 单用户开发阶段固定 userId，后续对接认证体系后从请求中获取 */
    private static final String CURRENT_USER_ID = "1";

    private final ReactAgent reactAgent;

    public AgentServiceImpl(ReactAgent reactAgent) {
        this.reactAgent = reactAgent;
    }

    @Override
    public Flux<String> streamChat(AgentRequest request) {
        if (!StringUtils.hasText(request.getMessage())) {
            return Flux.error(new IllegalArgumentException("消息内容不能为空"));
        }
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId()
                : UUID.randomUUID().toString();
        log.info("Agent streamChat - sessionId: {}, message: {}", sessionId, request.getMessage());

        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .addMetadata("user_id", CURRENT_USER_ID)
                .build();

        return Flux.defer(() -> {
            try {
                return reactAgent.stream(request.getMessage(), config);
            } catch (Exception e) {
                return Flux.<com.alibaba.cloud.ai.graph.NodeOutput>error(e);
            }
        })
                .filter(NodeOutput::isEND)
                .map(nodeOutput -> {
                    List<?> messages = nodeOutput.state().value("messages", List.class).orElse(List.of());
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        if (messages.get(i) instanceof AssistantMessage msg) {
                            return msg.getText() != null ? msg.getText() : "";
                        }
                    }
                    return "";
                })
                .doOnError(e -> log.error("Agent stream failed - sessionId: {}", sessionId, e));
    }

    @Override
    public AgentResponse chat(AgentRequest request) {
        if (!StringUtils.hasText(request.getMessage())) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId()
                : UUID.randomUUID().toString();
        log.info("Agent chat - sessionId: {}, message: {}", sessionId, request.getMessage());
        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId)
                    .addMetadata("user_id", CURRENT_USER_ID)
                    .build();
            AssistantMessage response = reactAgent.call(request.getMessage(), config);
            String reply = (response != null && response.getText() != null) ? response.getText() : "";
            return new AgentResponse(reply, sessionId);
        } catch (Exception e) {
            log.error("Agent call failed - sessionId: {}", sessionId, e);
            throw new RuntimeException("AI 助手暂时无法响应，请稍后重试", e);
        }
    }
}
