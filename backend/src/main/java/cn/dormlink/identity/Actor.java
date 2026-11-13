package cn.dormlink.identity;

import cn.dormlink.shared.api.ApiException;

/** Authenticated identity, including the server-validated session and client channel. */
public record Actor(
        String id,
        String name,
        String role,
        String studentId,
        String csrf,
        String sessionId,
        String clientType,
        boolean mustChangePassword) {
    public Actor(
            String id,
            String name,
            String role,
            String studentId,
            String csrf,
            String sessionId,
            String clientType) {
        this(id, name, role, studentId, csrf, sessionId, clientType, false);
    }

    public Actor(String id, String name, String role, String studentId, String csrf) {
        this(id, name, role, studentId, csrf, null, null);
    }

    public boolean manager() {
        return "MANAGER".equals(role);
    }

    public void requireManager() {
        if (!manager()) {
            throw new ApiException(403, "FORBIDDEN", "此操作仅限宿管");
        }
    }

    public void requireStudent() {
        if (!"STUDENT".equals(role)) {
            throw new ApiException(403, "FORBIDDEN", "此操作仅限学生");
        }
    }
}
