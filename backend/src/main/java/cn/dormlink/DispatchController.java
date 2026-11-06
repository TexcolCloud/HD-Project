package cn.dormlink;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dispatch")
class DispatchController {
  final AuthService auth;
  final DispatchPresence presence;
  final DispatchService dispatch;
  final BusinessSupport ops;

  DispatchController(
      AuthService auth, DispatchPresence presence, DispatchService dispatch, BusinessSupport ops) {
    this.auth = auth;
    this.presence = presence;
    this.dispatch = dispatch;
    this.ops = ops;
  }

  Object command(Map<String, Object> b, HttpServletRequest r, Supplier<Object> work) {
    return AuthController.ok(
        ops.command(r.getMethod() + r.getRequestURI(), r.getHeader("Idempotency-Key"), b, work), r);
  }

  @PostMapping("/heartbeat")
  Object heartbeat(HttpServletRequest r) {
    return AuthController.ok(presence.heartbeat(auth.sessionId(r)), r);
  }

  @GetMapping("/board")
  Object board(HttpServletRequest r) {
    return AuthController.ok(dispatch.board(), r);
  }

  @PatchMapping("/me")
  Object settings(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> presence.settings(b));
  }

  @PostMapping("/replan")
  Object replan(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return AuthController.ok(dispatch.replan(b), r);
  }

  @PostMapping("/tasks/{orderId}/claim")
  Object claim(
      @PathVariable String orderId, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> dispatch.claim(orderId, b));
  }

  @PostMapping("/tasks/{orderId}/handoff")
  Object handoff(
      @PathVariable String orderId, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> dispatch.handoff(orderId, b));
  }

  @PostMapping("/tasks/{orderId}/{action:depart|arrive|block|resume}")
  Object action(
      @PathVariable String orderId,
      @PathVariable String action,
      @RequestBody Map<String, Object> b,
      HttpServletRequest r) {
    return command(b, r, () -> dispatch.action(orderId, action, b));
  }

  @GetMapping("/travel")
  Object travel(HttpServletRequest r) {
    return AuthController.ok(dispatch.travel(), r);
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
