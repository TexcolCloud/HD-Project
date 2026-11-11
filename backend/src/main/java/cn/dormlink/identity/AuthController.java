package cn.dormlink.identity;

import cn.dormlink.shared.jdbc.Db;
import cn.dormlink.shared.api.ApiResponses;

import cn.dormlink.identity.Actor;
import cn.dormlink.shared.api.ApiException;

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

  @GetMapping("/health")
  Object health(HttpServletRequest r) {
    db.count("SELECT COUNT(*) FROM sys_user");
    return ApiResponses.ok(Map.of("status", "UP", "database", "UP"), r);
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
    return ApiResponses.ok(Map.of("token", token, "headerName", "X-XSRF-TOKEN"), r);
  }

  @PostMapping("/auth/login")
  Object login(@RequestBody Map<String, Object> b, HttpServletRequest r, HttpServletResponse s) {
    return ApiResponses.ok(auth.login(b, r, s), r);
  }

  @GetMapping("/auth/me")
  Object me(HttpServletRequest r) {
    return ApiResponses.ok(auth.profile(auth.current()), r);
  }

  @PostMapping("/auth/logout")
  Object logout(HttpServletRequest r, HttpServletResponse s) {
    auth.logout(r, s);
    return ApiResponses.ok(Map.of("loggedOut", true), r);
  }

  @PostMapping("/auth/change-password")
  Object password(@RequestBody Map<String, Object> b, HttpServletRequest r, HttpServletResponse s) {
    auth.changePassword(b, s);
    return ApiResponses.ok(Map.of("changed", true), r);
  }
}
