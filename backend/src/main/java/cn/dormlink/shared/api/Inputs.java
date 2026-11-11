package cn.dormlink.shared.api;

import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

public final class Inputs {
    public static String text(Map<String, Object> b, String key, int max, boolean required) {
        var raw = b.get(key);
        if (raw != null && !(raw instanceof String)) {
            throw ApiException.bad(key + "必须是文字");
        }
        String value = raw == null ? "" : raw.toString().trim();
        if ((required && value.isBlank()) || value.length() > max) {
            throw ApiException.bad(key + "不能为空且长度不超过" + max);
        }
        return value;
    }

    public static long id(Map<String, Object> b, String k) {
        try {
            long v = Long.parseLong(Objects.toString(b.get(k), ""));
            if (v <= 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (Exception e) {
            throw ApiException.bad(k + "无效");
        }
    }

    public static Long optionalId(Map<String, Object> b, String k) {
        return b.get(k) == null || b.get(k).toString().isBlank() ? null : id(b, k);
    }

    public static int integer(Map<String, Object> b, String key, int min, int max) {
        Object v = b.get(key);
        try {
            int i = Integer.parseInt(Objects.toString(v, ""));
            if (i < min || i > max) {
                throw new Exception();
            }
            return i;
        } catch (Exception e) {
            throw ApiException.bad(key + "需在" + min + "至" + max + "之间");
        }
    }

    public static int version(Map<String, Object> b) {
        return integer(b, "expectedVersion", 0, Integer.MAX_VALUE);
    }

    public static String choice(Map<String, Object> b, String k, String... values) {
        String v = text(b, k, 40, true);
        if (!List.of(values).contains(v)) {
            throw ApiException.bad(k + "取值不正确");
        }
        return v;
    }

    public static boolean bool(Map<String, Object> b, String k, boolean fallback) {
        Object v = b.get(k);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean x) {
            return x;
        }
        throw ApiException.bad(k + "必须为布尔值");
    }

    public static List<String> ids(Map<String, Object> b, String k, int max) {
        Object raw = b.get(k);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list) || list.size() > max) {
            throw ApiException.bad(k + "最多" + max + "项");
        }
        List<String> ids = new ArrayList<>();
        for (Object v : list) {
            var m = Map.<String, Object>of(k, Objects.toString(v, ""));
            ids.add(Long.toString(id(m, k)));
        }
        if (ids.stream().distinct().count() != ids.size()) {
            throw ApiException.bad("图片引用不能重复");
        }
        return ids;
    }

    public static Timestamp time(Map<String, Object> b, String key, Instant fallback) {
        String v = text(b, key, 50, false);
        if (v.isBlank()) {
            return Timestamp.from(fallback);
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(v).toInstant());
        } catch (Exception e) {
            try {
                return Timestamp.from(
                        LocalDate.parse(v).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
            } catch (Exception x) {
                throw ApiException.bad(key + "时间格式无效");
            }
        }
    }

    public static void notFuture(Timestamp time) {
        if (time.toInstant().isAfter(Instant.now().plusSeconds(60))) {
            throw ApiException.bad("发生时间不能是未来");
        }
    }

    public static String phone(Map<String, Object> b, String k) {
        String s = text(b, k, 20, true);
        if (!s.matches("[+0-9 ()-]{6,20}")) {
            throw ApiException.bad("联系方式格式无效");
        }
        return s;
    }

    public static String mask(String phone) {
        return phone == null
                ? ""
                : phone.length() < 7
                        ? "***"
                        : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
