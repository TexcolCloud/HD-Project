package cn.dormlink.identity;

import cn.dormlink.shared.api.ApiException;
import cn.dormlink.shared.api.Inputs;
import cn.dormlink.shared.crypto.Digests;
import cn.dormlink.shared.jdbc.Db;

import jakarta.servlet.http.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {
    final Db db;
    final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(10);
    final boolean secure;
    final TransactionTemplate writes;
    final ApplicationEventPublisher events;
    private final LoginAttempts attempts = new LoginAttempts();

    AuthService(
            Db db,
            @Value("${app.cookie-secure}") boolean secure,
            PlatformTransactionManager transactions,
            ApplicationEventPublisher events) {
        this.db = db;
        this.secure = secure;
        this.writes = new TransactionTemplate(transactions);
        this.events = events;
    }

    void changePassword(Map<String, Object> body, HttpServletResponse response) {
        Actor actor = current();
        String original =
                db.one("SELECT password_hash FROM sys_user WHERE id=?", actor.id())
                        .get("passwordHash")
                        .toString();
        if (!matches(Inputs.text(body, "oldPassword", 128, true), original)) {
            throw ApiException.bad("旧密码不正确");
        }
        String next = Inputs.text(body, "newPassword", 128, true);
        if (next.length() < 10 || next.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.bad("新密码至少10位且UTF-8长度不超过72字节");
        }
        if (matches(next, original)) {
            throw ApiException.bad("新密码不能与原密码相同");
        } // BCrypt is deliberately outside the transaction; the comparison below prevents lost
        // updates.
        String encoded = encode(next);
        writes.executeWithoutResult(
                status -> {
                    int changed =
                            db.update(
                                    "UPDATE sys_user SET"
                                        + " password_hash=?,must_change_password=FALSE,"
                                        + " credential_expires_at=NULL,account_version=account_version+1"
                                        + " WHERE id=? AND password_hash=?",
                                    encoded,
                                    actor.id(),
                                    original);
                    if (changed != 1) {
                        throw new ApiException(409, "VERSION_CONFLICT", "密码已变更，请重新登录");
                    }
                    db.update(
                            "UPDATE auth_session SET revoked_at=? WHERE user_id=? AND revoked_at IS"
                                    + " NULL",
                            Timestamp.from(Instant.now()),
                            actor.id());
                    events.publishEvent(new SessionsRevoked(actor.id(), null));
                });
        clearCookies(response);
    }

    private void clearCookies(HttpServletResponse response) {
        setCookie(response, "HD_SESSION", "", true, 0);
        setCookie(response, "HD_CSRF", "", false, 0);
    }

    public String sessionId(HttpServletRequest request) {
        String value = request.getHeader("Authorization");
        String token =
                value != null && value.startsWith("Bearer ")
                        ? value.substring(7)
                        : cookie(request, "HD_SESSION");
        if (token == null) {
            throw new ApiException(401, "UNAUTHORIZED", "请先登录");
        }
        return db.one(
                        "SELECT id FROM auth_session WHERE token_hash=? AND user_id=? AND"
                                + " revoked_at IS NULL AND expires_at>?",
                        Digests.sha256(token),
                        current().id(),
                        Timestamp.from(Instant.now()))
                .get("id")
                .toString();
    }

    public String encode(String password) {
        return passwords.encode(password);
    }

    boolean matches(String raw, String encoded) {
        return passwords.matches(raw, encoded);
    }

    public Actor current() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof Actor a)) {
            throw new ApiException(401, "UNAUTHORIZED", "请先登录");
        }
        return a;
    }

    String cookie(HttpServletRequest r, String name) {
        if (r.getCookies() != null) {
            for (var c : r.getCookies()) {
                if (c.getName().equals(name)) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    void setCookie(
            HttpServletResponse r, String name, String token, boolean httpOnly, long maxAge) {
        r.addHeader(
                "Set-Cookie",
                ResponseCookie.from(name, token)
                        .path("/")
                        .httpOnly(httpOnly)
                        .secure(secure)
                        .sameSite("Lax")
                        .maxAge(maxAge)
                        .build()
                        .toString());
    }

    String random() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public Actor authenticate(String token) {
        if (token == null || token.length() > 200) {
            return null;
        }
        var r =
                db.find(
                        "SELECT u.id,u.display_name,u.role,u.must_change_password,s.csrf_token,s.id"
                            + " session_id,s.client_type,st.id student_id FROM auth_session s JOIN"
                            + " sys_user u ON u.id=s.user_id LEFT JOIN student st ON"
                            + " st.user_id=u.id WHERE s.token_hash=? AND s.revoked_at IS NULL AND"
                            + " s.expires_at>? AND u.enabled=TRUE AND (u.credential_expires_at IS"
                            + " NULL OR u.credential_expires_at>?)",
                        Digests.sha256(token),
                        Timestamp.from(Instant.now()),
                        Timestamp.from(Instant.now()));
        return r == null
                ? null
                : new Actor(
                        r.get("id").toString(),
                        r.get("displayName").toString(),
                        r.get("role").toString(),
                        Objects.toString(r.get("studentId"), null),
                        r.get("csrfToken").toString(),
                        r.get("sessionId").toString(),
                        r.get("clientType").toString(),
                        Boolean.TRUE.equals(r.get("mustChangePassword")));
    }

    Map<String, Object> login(
            Map<String, Object> b, HttpServletRequest request, HttpServletResponse response) {
        String type = Inputs.choice(b, "clientType", "WEB", "MOBILE");
        if (type.equals("WEB")) {
            String c = cookie(request, "HD_CSRF"), h = request.getHeader("X-XSRF-TOKEN");
            if (c == null
                    || h == null
                    || !MessageDigest.isEqual(
                            c.getBytes(StandardCharsets.UTF_8),
                            h.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(403, "CSRF_INVALID", "安全凭证已失效，请刷新登录页");
            }
        }
        String username = Inputs.text(b, "username", 64, true),
                password = Inputs.text(b, "password", 128, true);
        String attemptKey = Digests.sha256(request.getRemoteAddr() + "\n" + username);
        attempts.begin(attemptKey, Instant.now());
        var u = db.find("SELECT * FROM sys_user WHERE username=? AND enabled=TRUE", username);
        boolean expired =
                u != null
                        && u.get("credentialExpiresAt") != null
                        && !Instant.parse(u.get("credentialExpiresAt").toString())
                                .isAfter(Instant.now());
        if (u == null
                || expired
                || !passwords.matches(password, u.get("passwordHash").toString())) {
            throw new ApiException(401, "LOGIN_FAILED", "账号或密码不正确");
        }
        // Password work is outside the transaction. Serialize session creation with password,
        // account and permission maintenance, then recheck the exact credential snapshot.
        return writes.execute(
                status -> {
                    var current =
                            db.one("SELECT * FROM sys_user WHERE id=? FOR UPDATE", u.get("id"));
                    boolean credentialExpired =
                            current.get("credentialExpiresAt") != null
                                    && !Instant.parse(current.get("credentialExpiresAt").toString())
                                            .isAfter(Instant.now());
                    if (!Boolean.TRUE.equals(current.get("enabled"))
                            || credentialExpired
                            || !Objects.equals(current.get("passwordHash"), u.get("passwordHash"))
                            || !Objects.equals(
                                    current.get("accountVersion"), u.get("accountVersion"))) {
                        throw new ApiException(401, "LOGIN_FAILED", "账号或密码已变更，请重新登录");
                    }
                    attempts.succeeded(attemptKey);
                    return createSession(current, type, response);
                });
    }

    private Map<String, Object> createSession(
            Map<String, Object> user, String type, HttpServletResponse response) {
        String token = random(), csrf = random();
        String id = user.get("id").toString();
        db.insert(
                "INSERT INTO"
                    + " auth_session(user_id,token_hash,csrf_token,client_type,expires_at,created_at)"
                    + " VALUES(?,?,?,?,?,?)",
                id,
                Digests.sha256(token),
                csrf,
                type,
                Timestamp.from(Instant.now().plusSeconds(28800)),
                Timestamp.from(Instant.now()));
        var student = db.find("SELECT id FROM student WHERE user_id=?", id);
        Actor actor =
                new Actor(
                        id,
                        user.get("displayName").toString(),
                        user.get("role").toString(),
                        student == null ? null : student.get("id").toString(),
                        csrf);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", profile(actor));
        if (type.equals("WEB")) {
            setCookie(response, "HD_SESSION", token, true, 28800);
            setCookie(response, "HD_CSRF", csrf, false, 28800);
        } else {
            out.put("token", token);
        }
        return out;
    }

    Map<String, Object> profile(Actor a) {
        var u =
                db.one(
                        "SELECT id,username,display_name,role,must_change_password FROM sys_user"
                                + " WHERE id=?",
                        a.id());
        u.put(
                "buildingIds",
                db
                        .rows(
                                "SELECT building_id FROM manager_building WHERE manager_user_id=?"
                                        + " ORDER BY building_id",
                                a.id())
                        .stream()
                        .map(x -> x.get("buildingId"))
                        .toList());
        u.put("studentId", a.studentId());
        u.put(
                "currentRoom",
                a.studentId() == null
                        ? null
                        : db.find(
                                "SELECT r.*,b.name building_name,(SELECT COUNT(*) FROM residence x"
                                    + " WHERE x.room_id=r.id AND x.checked_out_at IS NULL)"
                                    + " occupied_count FROM residence h JOIN dorm_room r ON"
                                    + " r.id=h.room_id JOIN building b ON b.id=r.building_id WHERE"
                                    + " h.student_id=? AND h.checked_out_at IS NULL",
                                a.studentId()));
        return u;
    }

    @Transactional
    void logout(HttpServletRequest r, HttpServletResponse s) {
        String t = r.getHeader("Authorization");
        t = t != null && t.startsWith("Bearer ") ? t.substring(7) : cookie(r, "HD_SESSION");
        if (t != null) {
            db.update(
                    "UPDATE auth_session SET revoked_at=? WHERE token_hash=?",
                    Timestamp.from(Instant.now()),
                    Digests.sha256(t));
        }
        events.publishEvent(new SessionsRevoked(current().id(), current().sessionId()));
        clearCookies(s);
    }
}
