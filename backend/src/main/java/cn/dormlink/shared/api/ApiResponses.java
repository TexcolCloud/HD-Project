package cn.dormlink.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** Shared success envelope and request trace propagation for API modules. */
public final class ApiResponses {
    private ApiResponses() {}

    public record Envelope<T>(T data, String traceId) {}

    public static <T> Envelope<T> ok(T data, HttpServletRequest request) {
        return new Envelope<>(data, trace(request));
    }

    public static String trace(HttpServletRequest request) {
        Object value = request.getAttribute("traceId");
        if (value == null) {
            value = UUID.randomUUID().toString();
            request.setAttribute("traceId", value);
        }
        return value.toString();
    }
}
