package cn.dormlink.fieldservice;

import cn.dormlink.shared.api.ApiResponses;
import cn.dormlink.shared.application.BusinessSupport;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/hygiene-remediations")
class RemediationController {
    private final RemediationService service;
    private final BusinessSupport ops;

    RemediationController(RemediationService service, BusinessSupport ops) {
        this.service = service;
        this.ops = ops;
    }

    @GetMapping
    Object list(@RequestParam Map<String, String> query, HttpServletRequest request) {
        return ApiResponses.ok(service.list(query), request);
    }

    @GetMapping("/{id}")
    Object detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponses.ok(service.detail(id), request);
    }

    @PostMapping
    Object create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return command(null, body, request, () -> service.create(body));
    }

    @PostMapping("/{id}/feedback")
    Object feedback(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return command(id, body, request, () -> service.feedback(id, body));
    }

    @PatchMapping("/{id}")
    Object assign(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return command(id, body, request, () -> service.assign(id, body));
    }

    @PostMapping("/{id}/cancel")
    Object cancel(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return command(id, body, request, () -> service.cancel(id, body));
    }

    @PostMapping("/{id}/review")
    Object review(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return command(id, body, request, () -> service.review(id, body));
    }

    private Object command(
            String id,
            Map<String, Object> body,
            HttpServletRequest request,
            Supplier<Object> action) {
        // Cached replies must still respect a responsibility change made after the original
        // command.
        if (id != null) {
            service.authorize(id);
        }
        return ApiResponses.ok(
                ops.command(
                        request.getMethod() + request.getRequestURI(),
                        request.getHeader("Idempotency-Key"),
                        body,
                        action),
                request);
    }
}
