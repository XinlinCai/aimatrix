package com.vectrans.aimatrix.service;

import com.vectrans.aimatrix.dto.AgentRequest;
import com.vectrans.aimatrix.dto.AgentResponse;

public interface AgentService {

    AgentResponse chat(AgentRequest request);
}
