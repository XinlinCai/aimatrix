package com.vectrans.aimatrix;

import com.vectrans.aimatrix.dto.AgentRequest;
import com.vectrans.aimatrix.dto.AgentResponse;
import com.vectrans.aimatrix.service.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class AimatrixApplicationTests {

    @Autowired
    private AgentService agentService;

    @Test
    void contextLoads() {
    }

    @Test
    void testAgentChain() {
        System.out.println("\n========== ReactAgent → DashScope 链路验证 ==========");
        AgentRequest request = new AgentRequest("你好，请用一句话介绍你自己，你可以做什么呀？你用的是那个模型啊？杭州今天的天气怎么样呀？", "verify-001");
        AgentResponse response = agentService.chat(request);

        System.out.println("用户: " + request.getMessage());
        System.out.println("AI: " + response.getReply());
        System.out.println("SessionId: " + response.getSessionId());

        assertNotNull(response.getReply(), "Agent 回复不应为 null");
        assertFalse(response.getReply().isBlank(), "Agent 回复不应为空");
        System.out.println("✅ ReactAgent → DashScope 链路正常");
    }

}
