package cn.dormlink.dispatch;

import cn.dormlink.identity.AuthService;
import cn.dormlink.shared.api.ApiResponses;
import cn.dormlink.shared.application.BusinessSupport;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/dispatch")
class DispatchController {
    final AuthService auth;
    final DispatchPresence presence;
    final DispatchService dispatch;
    final DispatchExecutionService execution;
    final BusinessSupport ops;

    DispatchController(
            AuthService auth,
            DispatchPresence presence,
            DispatchService dispatch,
            DispatchExecutionService execution,
            BusinessSupport ops) {
        this.auth = auth;
        this.presence = presence;
        this.dispatch = dispatch;
        this.execution = execution;
        this.ops = ops;
    }

    Object command(Map<String, Object> b, HttpServletRequest r, Supplier<Object> work) {
        return ApiResponses.ok(
                ops.command(
                        r.getMethod() + r.getRequestURI(), r.getHeader("Idempotency-Key"), b, work),
                r);
    }

    @PostMapping("/heartbeat")
    Object heartbeat(HttpServletRequest r) {
        return ApiResponses.ok(presence.heartbeat(auth.sessionId(r)), r);
    }

    @GetMapping("/board")
    Object board(HttpServletRequest r) {
        return ApiResponses.ok(dispatch.board(), r);
    }

    @PatchMapping("/me")
    Object settings(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        return command(b, r, () -> presence.settings(b));
    }

    @PostMapping("/replan")
    Object replan(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        return ApiResponses.ok(dispatch.replan(b), r);
    }

    @PostMapping("/tasks/{orderId}/claim")
    Object claim(
            @PathVariable String orderId,
            @RequestBody Map<String, Object> b,
            HttpServletRequest r) {
        return command(b, r, () -> execution.claim(orderId, b));
    }

    @PostMapping("/tasks/{orderId}/handoff")
    Object handoff(
            @PathVariable String orderId,
            @RequestBody Map<String, Object> b,
            HttpServletRequest r) {
        return command(b, r, () -> execution.handoff(orderId, b));
    }

    @PostMapping("/tasks/{orderId}/takeover")
    Object takeover(
            @PathVariable String orderId,
            @RequestBody Map<String, Object> b,
            HttpServletRequest r) {
        return command(b, r, () -> execution.takeover(orderId, b));
    }

    @PostMapping("/tasks/{orderId}/{action:depart|arrive|block|resume}")
    Object action(
            @PathVariable String orderId,
            @PathVariable String action,
            @RequestBody Map<String, Object> b,
            HttpServletRequest r) {
        return command(b, r, () -> execution.action(orderId, action, b));
    }

    @GetMapping("/travel")
    Object travel(HttpServletRequest r) {
        return ApiResponses.ok(dispatch.travel(), r);
    }

    @PatchMapping("/travel/{fromId}/{toId}")
    Object travel(
            @PathVariable String fromId,
            @PathVariable String toId,
            @RequestBody Map<String, Object> b,
            HttpServletRequest r) {
        return command(b, r, () -> dispatch.saveTravel(fromId, toId, b));
    }
}
