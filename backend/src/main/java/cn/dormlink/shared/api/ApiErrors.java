package cn.dormlink.shared.api;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> known(ApiException e, HttpServletRequest r) {
        return ResponseEntity.status(e.status()).body(error(e.code(), e.getMessage(), r));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> duplicate(DataIntegrityViolationException e, HttpServletRequest r) {
        return ResponseEntity.status(409)
                .body(error("DATA_CONFLICT", "记录重复、容量或数据约束发生冲突，请刷新后重试", r));
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
        return ResponseEntity.internalServerError()
                .body(error("INTERNAL_ERROR", "服务暂时不可用，请稍后重试", r));
    }

    public static Map<String, Object> error(String code, String text, HttpServletRequest r) {
        return Map.of(
                "error",
                Map.of("code", code, "message", text, "fieldErrors", List.of()),
                "traceId",
                trace(r));
    }

    public static String trace(HttpServletRequest r) {
        var t = r.getAttribute("traceId");
        if (t == null) {
            t = UUID.randomUUID().toString();
            r.setAttribute("traceId", t);
        }
        return t.toString();
    }
}
