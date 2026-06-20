package com.vectrans.aimatrix;

import com.vectrans.aimatrix.dto.AgentRequest;
import com.vectrans.aimatrix.dto.AgentResponse;
import com.vectrans.aimatrix.service.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void testStreamChat() {
        System.out.println("\n========== streamChat 流式响应验证 ==========");
        AgentRequest request = new AgentRequest("请用一句话介绍你自己", "stream-verify-001");

        Flux<String> stream = agentService.streamChat(request);
        List<String> results = stream.collectList().block();

        assertNotNull(results, "流式结果列表不应为 null");
        assertFalse(results.isEmpty(), "流式响应不应为空");

        String reply = results.get(results.size() - 1);
        System.out.println("用户: " + request.getMessage());
        System.out.println("AI: " + reply);

        assertNotNull(reply, "Agent 回复不应为 null");
        assertFalse(reply.isBlank(), "Agent 回复不应为空");
        System.out.println("✅ streamChat 流式响应正常");
    }

    @Test
    void testStreamChatEmptyMessage() {
        System.out.println("\n========== streamChat 空消息校验 ==========");
        AgentRequest request = new AgentRequest("", "stream-verify-002");

        Flux<String> stream = agentService.streamChat(request);
        assertThrows(IllegalArgumentException.class, () -> stream.collectList().block(),
                "空消息应抛出 IllegalArgumentException");
        System.out.println("✅ streamChat 空消息校验正常");
    }

}
