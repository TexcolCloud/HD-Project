package cn.dormlink;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

class ApiException extends RuntimeException {
  final int status;
  final String code;

  ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  static ApiException bad(String text) {
    return new ApiException(400, "VALIDATION_ERROR", text);
  }

  static ApiException missing() {
    return new ApiException(404, "RESOURCE_NOT_FOUND", "记录不存在或无权访问");
  }

  static ApiException conflict(String text) {
    return new ApiException(409, "BUSINESS_CONFLICT", text);
  }
}

@RestControllerAdvice
class ApiErrors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> known(ApiException e, HttpServletRequest r) {
    return ResponseEntity.status(e.status).body(error(e.code, e.getMessage(), r));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> duplicate(DataIntegrityViolationException e, HttpServletRequest r) {
    return ResponseEntity.status(409).body(error("DATA_CONFLICT", "记录重复、容量或数据约束发生冲突，请刷新后重试", r));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e, HttpServletRequest r) {
    return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", "请求参数格式无效", r));
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<?> large(Exception e, HttpServletRequest r) {
    return ResponseEntity.status(413).body(error("FILE_TOO_LARGE", "图片每张不能超过10MB", r));
  }

  @ExceptionHandler(javax.imageio.IIOException.class)
  ResponseEntity<?> corrupt(Exception e, HttpServletRequest r) {
    return ResponseEntity.status(415)
        .body(error("FILE_DECODE_FAILED", "图片损坏或无法解码，请重新选择JPG、PNG或WebP图片", r));
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<?> missing(Exception e, HttpServletRequest r) {
    return ResponseEntity.status(404).body(error("RESOURCE_NOT_FOUND", "接口或资源不存在", r));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unknown(Exception e, HttpServletRequest r) {
    LoggerFactory.getLogger(ApiErrors.class).error("Request failed {}", r.getRequestURI(), e);
    return ResponseEntity.internalServerError().body(error("INTERNAL_ERROR", "服务暂时不可用，请稍后重试", r));
  }

  static Map<String, Object> error(String code, String text, HttpServletRequest r) {
    return Map.of(
        "error",
        Map.of("code", code, "message", text, "fieldErrors", List.of()),
        "traceId",
        trace(r));
  }

  static String trace(HttpServletRequest r) {
    var t = r.getAttribute("traceId");
    if (t == null) {
      t = UUID.randomUUID().toString();
      r.setAttribute("traceId", t);
    }
    return t.toString();
  }
}

final class Inputs {
  static String text(Map<String, Object> b, String key, int max, boolean required) {
    var raw = b.get(key);
    if (raw != null && !(raw instanceof String)) throw ApiException.bad(key + "必须是文字");
    String value = raw == null ? "" : raw.toString().trim();
    if ((required && value.isBlank()) || value.length() > max)
      throw ApiException.bad(key + "不能为空且长度不超过" + max);
    return value;
  }

  static long id(Map<String, Object> b, String k) {
    try {
      long v = Long.parseLong(Objects.toString(b.get(k), ""));
      if (v <= 0) throw new NumberFormatException();
      return v;
    } catch (Exception e) {
      throw ApiException.bad(k + "无效");
    }
  }

  static Long optionalId(Map<String, Object> b, String k) {
    return b.get(k) == null || b.get(k).toString().isBlank() ? null : id(b, k);
  }

  static int integer(Map<String, Object> b, String key, int min, int max) {
    Object v = b.get(key);
    try {
      int i = Integer.parseInt(Objects.toString(v, ""));
      if (i < min || i > max) throw new Exception();
      return i;
    } catch (Exception e) {
      throw ApiException.bad(key + "需在" + min + "至" + max + "之间");
    }
  }

  static int version(Map<String, Object> b) {
    return integer(b, "expectedVersion", 0, Integer.MAX_VALUE);
  }

  static String choice(Map<String, Object> b, String k, String... values) {
    String v = text(b, k, 40, true);
    if (!List.of(values).contains(v)) throw ApiException.bad(k + "取值不正确");
    return v;
  }

  static boolean bool(Map<String, Object> b, String k, boolean fallback) {
    Object v = b.get(k);
    if (v == null) return fallback;
    if (v instanceof Boolean x) return x;
    throw ApiException.bad(k + "必须为布尔值");
  }

  static List<String> ids(Map<String, Object> b, String k, int max) {
    Object raw = b.get(k);
    if (raw == null) return List.of();
    if (!(raw instanceof List<?> list) || list.size() > max)
      throw ApiException.bad(k + "最多" + max + "项");
    List<String> ids = new ArrayList<>();
    for (Object v : list) {
      var m = Map.<String, Object>of(k, Objects.toString(v, ""));
      ids.add(Long.toString(id(m, k)));
    }
    if (ids.stream().distinct().count() != ids.size()) throw ApiException.bad("图片引用不能重复");
    return ids;
  }

  static Timestamp time(Map<String, Object> b, String key, Instant fallback) {
    String v = text(b, key, 50, false);
    if (v.isBlank()) return Timestamp.from(fallback);
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

  static void notFuture(Timestamp time) {
    if (time.toInstant().isAfter(Instant.now().plusSeconds(60)))
      throw ApiException.bad("发生时间不能是未来");
  }

  static String phone(Map<String, Object> b, String k) {
    String s = text(b, k, 20, true);
    if (!s.matches("[+0-9 ()-]{6,20}")) throw ApiException.bad("联系方式格式无效");
    return s;
  }

  static String mask(String phone) {
    return phone == null
        ? ""
        : phone.length() < 7
            ? "***"
            : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
  }
}
