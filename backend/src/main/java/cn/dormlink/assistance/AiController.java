package cn.dormlink.assistance;

import cn.dormlink.shared.api.ApiResponses;
import cn.dormlink.shared.application.BusinessSupport;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
class AiController {
    private final AiService student;
    private final ManagerAssistService manager;
    private final AssistFeedbackService feedback;
    private final BusinessSupport ops;

    AiController(
            AiService student,
            ManagerAssistService manager,
            AssistFeedbackService feedback,
            BusinessSupport ops) {
        this.student = student;
        this.manager = manager;
        this.feedback = feedback;
        this.ops = ops;
    }

    @PostMapping("/repair-suggestions")
    Object suggest(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponses.ok(student.suggest(command(body, request)), request);
    }

    @PostMapping("/manager/brief")
    Object brief(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponses.ok(manager.brief(command(body, request)), request);
    }

    @PostMapping("/manager/repairs/{id}")
    Object analyze(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return ApiResponses.ok(manager.analyze(id, command(body, request)), request);
    }

    private AiCommand command(Map<String, Object> body, HttpServletRequest request) {
        return new AiCommand(request.getRequestURI(), request.getHeader("Idempotency-Key"), body);
    }

    @GetMapping("/manager/repairs/{id}/feedback")
    Object feedback(@PathVariable String id, HttpServletRequest request) {
        return ApiResponses.ok(feedback.list(id), request);
    }

    @PostMapping("/manager/repairs/{id}/feedback")
    Object recordFeedback(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return ApiResponses.ok(
                ops.command(
                        request.getMethod() + request.getRequestURI(),
                        request.getHeader("Idempotency-Key"),
                        body,
                        () -> feedback.record(id, body)),
                request);
    }
}
