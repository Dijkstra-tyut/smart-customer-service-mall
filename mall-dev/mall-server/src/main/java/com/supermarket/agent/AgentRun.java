package com.supermarket.agent;

/** Safe immutable identity; credential hashes never leave the repository. */
public record AgentRun(String id, long sessionId, int userId, String status,
                       int revision, String humanTaskId) {
    public boolean terminal() {
        return "completed".equals(status) || "cancelled".equals(status) || "failed".equals(status);
    }
}
