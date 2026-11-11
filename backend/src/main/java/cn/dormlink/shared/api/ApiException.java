package cn.dormlink.shared.api;

/** Expected API failure. The wire code is stable across all clients. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException bad(String text) {
        return new ApiException(400, "VALIDATION_ERROR", text);
    }

    public static ApiException missing() {
        return new ApiException(404, "RESOURCE_NOT_FOUND", "记录不存在或无权访问");
    }

    public static ApiException conflict(String text) {
        return new ApiException(409, "BUSINESS_CONFLICT", text);
    }
}
