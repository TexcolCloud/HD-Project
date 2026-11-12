package cn.dormlink.identity;

import cn.dormlink.shared.api.ApiErrors;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, AuthService auth, JsonMapper json)
            throws Exception {
        var filter =
                new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(
                            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                            throws ServletException, IOException {
                        ApiErrors.trace(req);
                        String bearer = req.getHeader("Authorization");
                        boolean mobile = bearer != null && bearer.startsWith("Bearer ");
                        Actor actor =
                                auth.authenticate(
                                        mobile
                                                ? bearer.substring(7)
                                                : auth.cookie(req, "HD_SESSION"));
                        if (actor != null) {
                            SecurityContextHolder.getContext()
                                    .setAuthentication(
                                            new UsernamePasswordAuthenticationToken(
                                                    actor, null, List.of()));
                            if (actor.mustChangePassword()
                                    && req.getRequestURI().startsWith("/api/v1/")
                                    && !Set.of(
                                                    "/api/v1/auth/me",
                                                    "/api/v1/auth/csrf",
                                                    "/api/v1/auth/change-password",
                                                    "/api/v1/auth/logout",
                                                    "/api/v1/health")
                                            .contains(req.getRequestURI())) {
                                fail(
                                        res,
                                        403,
                                        "PASSWORD_CHANGE_REQUIRED",
                                        "请先修改临时密码后继续使用",
                                        req,
                                        json);
                                return;
                            }
                            if (!mobile
                                    && !Set.of("GET", "HEAD", "OPTIONS")
                                            .contains(req.getMethod())) {
                                String csrf = req.getHeader("X-XSRF-TOKEN");
                                if (csrf == null
                                        || !MessageDigest.isEqual(
                                                csrf.getBytes(StandardCharsets.UTF_8),
                                                actor.csrf().getBytes(StandardCharsets.UTF_8))) {
                                    fail(res, 403, "CSRF_INVALID", "安全凭证无效，请刷新页面", req, json);
                                    return;
                                }
                            }
                        }
                        chain.doFilter(req, res);
                    }
                };
        return http.csrf(c -> c.disable())
                .cors(c -> c.configurationSource(cors()))
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .authorizeHttpRequests(
                        c ->
                                c.requestMatchers(
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/csrf",
                                                "/api/v1/health")
                                        .permitAll()
                                        .requestMatchers("/api/v1/**")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .exceptionHandling(
                        c ->
                                c.authenticationEntryPoint(
                                                (req, res, e) ->
                                                        fail(
                                                                res,
                                                                401,
                                                                "UNAUTHORIZED",
                                                                "请先登录",
                                                                req,
                                                                json))
                                        .accessDeniedHandler(
                                                (req, res, e) ->
                                                        fail(
                                                                res,
                                                                403,
                                                                "FORBIDDEN",
                                                                "无权执行此操作",
                                                                req,
                                                                json)))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    static void fail(
            HttpServletResponse res,
            int status,
            String code,
            String message,
            HttpServletRequest req,
            JsonMapper json)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(json.writeValueAsString(ApiErrors.error(code, message, req)));
    }

    CorsConfigurationSource cors() {
        var c = new CorsConfiguration();
        c.setAllowedOrigins(
                List.of(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://localhost:4173",
                        "http://127.0.0.1:4173"));
        c.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(
                List.of("Content-Type", "Authorization", "X-XSRF-TOKEN", "Idempotency-Key"));
        c.setAllowCredentials(true);
        var s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/api/**", c);
        return s;
    }
}
