package com.vectrans.aimatrix.service.impl;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.vectrans.aimatrix.dto.AgentRequest;
import com.vectrans.aimatrix.dto.AgentResponse;
import com.vectrans.aimatrix.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private final ReactAgent reactAgent;

    public AgentServiceImpl(ReactAgent reactAgent) {
        this.reactAgent = reactAgent;
    }

    @Override
    public AgentResponse chat(AgentRequest request) {
        String sessionId = resolveSessionId(request.getSessionId());

        log.info("Agent chat - sessionId: {}, message: {}", sessionId, request.getMessage());

        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId)
                    .build();

            AssistantMessage response = reactAgent.call(request.getMessage(), config);
            String reply = response.getText();

            log.info("Agent reply - sessionId: {}, reply length: {}", sessionId, reply.length());

            return new AgentResponse(reply, sessionId);
        } catch (Exception e) {
            log.error("Agent call failed - sessionId: {}", sessionId, e);
            throw new RuntimeException("AI agent call failed: " + e.getMessage(), e);
        }
    }

    private String resolveSessionId(String sessionId) {
        return (sessionId != null && !sessionId.isBlank())
                ? sessionId
                : UUID.randomUUID().toString();
    }
}
