package cn.dormlink.identity;

/** A committed identity change revokes one session, or every session when sessionId is null. */
public record SessionsRevoked(String userId, String sessionId) {}
