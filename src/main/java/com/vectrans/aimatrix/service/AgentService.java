package com.vectrans.aimatrix.service;

import com.vectrans.aimatrix.dto.AgentRequest;
import com.vectrans.aimatrix.dto.AgentResponse;
import reactor.core.publisher.Flux;

public interface AgentService {

    AgentResponse chat(AgentRequest request);

    Flux<String> streamChat(AgentRequest request);
}
