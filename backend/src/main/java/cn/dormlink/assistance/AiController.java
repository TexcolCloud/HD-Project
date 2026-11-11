package cn.dormlink.assistance;

import cn.dormlink.shared.api.ApiResponses;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai")
class AiController {
  private final AiService student;
  private final ManagerAssistService manager;

  AiController(AiService student, ManagerAssistService manager) {
    this.student = student;
    this.manager = manager;
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
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ApiResponses.ok(manager.analyze(id, command(body, request)), request);
  }

  private AiCommand command(Map<String, Object> body, HttpServletRequest request) {
    return new AiCommand(request.getRequestURI(), request.getHeader("Idempotency-Key"), body);
  }
}
