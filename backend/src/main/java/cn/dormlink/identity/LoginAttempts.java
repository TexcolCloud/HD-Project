package cn.dormlink.identity;

import cn.dormlink.shared.api.ApiException;

import java.time.Instant;
import java.util.LinkedHashMap;

/** Single-service login backoff; bounded keys hold only one-way account/source digests. */
final class LoginAttempts {
    private static final int MAX_KEYS = 10000;
    private final LinkedHashMap<String, Attempt> entries = new LinkedHashMap<>();

    synchronized void begin(String key, Instant now) {
        Attempt a = entries.get(key);
        if (a != null && a.until().isAfter(now) && a.failures() >= 5) {
            throw new ApiException(429, "LOGIN_THROTTLED", "登录尝试过多，请5分钟后重试");
        }
        if (a != null && !a.until().isAfter(now)) {
            entries.remove(key);
            a = null;
        }
        if (a == null && entries.size() >= MAX_KEYS) {
            var oldest = entries.entrySet().iterator();
            int examined = 0;
            while (oldest.hasNext() && examined++ < 100) {
                if (!oldest.next().getValue().until().isAfter(now)) {
                    oldest.remove();
                }
            }
            if (entries.size() >= MAX_KEYS) {
                throw new ApiException(429, "LOGIN_THROTTLED", "登录请求过多，请稍后重试");
            }
        }
        // Reserve before password work so parallel requests cannot bypass the failure budget.
        entries.put(key, new Attempt(a == null ? 1 : a.failures() + 1, now.plusSeconds(300)));
    }

    synchronized void succeeded(String key) {
        entries.remove(key);
    }

    private record Attempt(int failures, Instant until) {}
}
