package cn.dormlink.identity;

import cn.dormlink.shared.api.ApiResponses;
import cn.dormlink.shared.jdbc.Db;

import jakarta.servlet.http.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
class AuthController {
    final AuthService auth;
    final Db db;
    final AccountSecurityService security;

    AuthController(AuthService auth, Db db, AccountSecurityService security) {
        this.auth = auth;
        this.db = db;
        this.security = security;
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
                && a.getPrincipal() instanceof Actor user) {
            token = user.csrf();
        } else {
            token = auth.random();
        }
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
    Object password(
            @RequestBody Map<String, Object> b, HttpServletRequest r, HttpServletResponse s) {
        auth.changePassword(b, s);
        return ApiResponses.ok(Map.of("changed", true), r);
    }

    @PostMapping("/auth/phone/challenges")
    Object requestPhone(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponses.ok(security.requestPhone(body, request), request);
    }

    @PostMapping("/auth/activate")
    Object activate(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var result = security.submitPhone(body, request, AccountSecurityService.Purpose.ACTIVATE);
        auth.clearCookies(response);
        return ApiResponses.ok(result, request);
    }

    @PostMapping("/auth/phone/bind")
    Object bindPhone(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponses.ok(
                security.submitPhone(body, request, AccountSecurityService.Purpose.BIND), request);
    }

    @PostMapping("/auth/phone/change")
    Object changePhone(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var result = security.changePhone(body, request);
        auth.clearCookies(response);
        return ApiResponses.ok(result, request);
    }

    @PostMapping("/auth/recovery-code/replace")
    Object replaceRecoveryCode(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponses.ok(
                security.submitPhone(
                        body, request, AccountSecurityService.Purpose.REPLACE_RECOVERY_CODE),
                request);
    }

    @PostMapping("/auth/password-recovery/challenges")
    Object requestRecovery(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponses.ok(security.requestRecovery(body, request), request);
    }

    @PostMapping("/auth/password-recovery/verify")
    Object verifyRecovery(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponses.ok(security.verifyRecovery(body, request), request);
    }

    @PostMapping("/auth/password-recovery/complete")
    Object completeRecovery(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        security.completeRecovery(body, request);
        auth.clearCookies(response);
        return ApiResponses.ok(null, request);
    }
}
