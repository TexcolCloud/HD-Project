package cn.dormlink.followup;

import cn.dormlink.shared.api.ApiResponses;
import cn.dormlink.shared.application.BusinessSupport;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/messages")
class ServiceMessageController {
    private final ServiceMessageService service;
    private final BusinessSupport ops;

    ServiceMessageController(ServiceMessageService service, BusinessSupport ops) {
        this.service = service;
        this.ops = ops;
    }

    @GetMapping
    Object list(@RequestParam Map<String, String> query, HttpServletRequest request) {
        return ApiResponses.ok(service.list(query), request);
    }

    @GetMapping("/summary")
    Object summary(HttpServletRequest request) {
        return ApiResponses.ok(service.summary(), request);
    }

    @PostMapping("/{id}/read")
    Object read(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return ApiResponses.ok(
                ops.command(
                        request.getMethod() + request.getRequestURI(),
                        request.getHeader("Idempotency-Key"),
                        body,
                        () -> service.read(id, body)),
                request);
    }
}
