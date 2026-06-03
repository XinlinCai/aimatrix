package com.vectrans.aimatrix;

import com.vectrans.aimatrix.dto.AgentRequest;
import com.vectrans.aimatrix.dto.AgentResponse;
import com.vectrans.aimatrix.service.AgentService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class AimatrixApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(AimatrixApplication.class, args);

        AgentService agentService = context.getBean(AgentService.class);

        System.out.println("\n========== ReactAgent → Higress → LLM 链路验证 ==========");
        AgentRequest request = new AgentRequest("你好，请用一句话介绍你自己，你可以做什么呀？你用的是那个模型啊？", "verify-001");
        AgentResponse response = agentService.chat(request);
        System.out.println("用户: " + request.getMessage());
        System.out.println("AI: " + response.getReply());
        System.out.println("SessionId: " + response.getSessionId());
        System.out.println("结果: " + (response.getReply() != null && !response.getReply().isBlank() ? "✅ 链路正常" : "❌ 链路异常"));
    }
}
