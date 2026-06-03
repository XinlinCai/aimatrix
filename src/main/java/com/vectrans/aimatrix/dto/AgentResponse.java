package com.vectrans.aimatrix.dto;

public class AgentResponse {

    private String reply;
    private String sessionId;

    public AgentResponse() {
    }

    public AgentResponse(String reply, String sessionId) {
        this.reply = reply;
        this.sessionId = sessionId;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
