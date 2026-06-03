package com.vectrans.aimatrix.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Value("${agent.name:aimatrix-agent}")
    private String agentName;

    @Value("${agent.max-iterations:10}")
    private int maxIterations;

    @Bean
    public ReactAgent reactAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name(agentName)
                .model(chatModel)
                .instruction("你是一个专业、友好的AI助手，名叫AIMatrix。请用中文回答用户的问题。")
                .hooks(ModelCallLimitHook.builder()
                        .runLimit(maxIterations)
                        .build())
                .saver(new MemorySaver())
                .build();
    }
}
