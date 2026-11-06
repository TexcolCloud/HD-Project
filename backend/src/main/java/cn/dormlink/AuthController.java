package cn.dormlink;

import jakarta.servlet.http.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class AuthController {
  final AuthService auth;
  final Db db;

  AuthController(AuthService auth, Db db) {
    this.auth = auth;
    this.db = db;
  }

  static Map<String, Object> ok(Object data, HttpServletRequest r) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("data", data);
    out.put("traceId", ApiErrors.trace(r));
    return out;
  }

  @GetMapping("/health")
  Object health(HttpServletRequest r) {
    db.count("SELECT COUNT(*) FROM sys_user");
    return ok(Map.of("status", "UP", "database", "UP"), r);
  }

  @GetMapping("/auth/csrf")
  Object csrf(HttpServletRequest r, HttpServletResponse s) {
    String token;
    Object p =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (p instanceof org.springframework.security.core.Authentication a
        && a.getPrincipal() instanceof Actor user) token = user.csrf();
    else token = auth.random();
    auth.setCookie(s, "HD_CSRF", token, false, 28800);
    return ok(Map.of("token", token, "headerName", "X-XSRF-TOKEN"), r);
  }

  @PostMapping("/auth/login")
  Object login(@RequestBody Map<String, Object> b, HttpServletRequest r, HttpServletResponse s) {
    return ok(auth.login(b, r, s), r);
  }

  @GetMapping("/auth/me")
  Object me(HttpServletRequest r) {
    return ok(auth.profile(auth.current()), r);
  }

  @PostMapping("/auth/logout")
  Object logout(HttpServletRequest r, HttpServletResponse s) {
    auth.logout(r, s);
    return ok(Map.of("loggedOut", true), r);
  }

  @PostMapping("/auth/change-password")
  Object password(@RequestBody Map<String, Object> b, HttpServletRequest r, HttpServletResponse s) {
    var a = auth.current();
    var u = db.one("SELECT password_hash FROM sys_user WHERE id=?", a.id());
    if (!auth.matches(Inputs.text(b, "oldPassword", 128, true), u.get("passwordHash").toString()))
      throw ApiException.bad("旧密码不正确");
    String next = Inputs.text(b, "newPassword", 128, true);
    if (next.length() < 10) throw ApiException.bad("新密码至少10位");
    db.update("UPDATE sys_user SET password_hash=? WHERE id=?", auth.encode(next), a.id());
    db.update(
        "UPDATE auth_session SET revoked_at=? WHERE user_id=?",
        Timestamp.from(Instant.now()),
        a.id());
    auth.logout(r, s);
    return ok(Map.of("changed", true), r);
  }
}
