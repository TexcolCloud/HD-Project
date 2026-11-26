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
    private static final SecureRandom RANDOM = new SecureRandom();
    private final boolean aiConfigured;
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
            ApplicationEventPublisher events,
            @Value("${app.ai.api-key:}") String aiApiKey) {
        this.db = db;
        this.secure = secure;
        this.writes = new TransactionTemplate(transactions);
        this.events = events;
        this.aiConfigured = !aiApiKey.isBlank();
    }

    void changePassword(Map<String, Object> body, HttpServletResponse response) {
        Actor actor = current();
        var snapshot = db.one("SELECT * FROM sys_user WHERE id=?", actor.id());
        String original = snapshot.get("passwordHash").toString();
        if (!matches(Inputs.text(body, "oldPassword", 128, true), original)) {
            throw ApiException.bad("旧密码不正确");
        }
        String encoded = encodeNewPassword(body, original);
        writes.executeWithoutResult(
                status -> {
                    db.one("SELECT id FROM sys_user WHERE id=? FOR UPDATE", actor.id());
                    requireActiveSession(actor);
                    int changed =
                            db.update(
                                    "UPDATE sys_user SET"
                                        + " password_hash=?,must_change_password=FALSE,"
                                        + " credential_expires_at=NULL,account_version=account_version+1"
                                        + " WHERE id=? AND enabled=TRUE AND password_hash=? AND"
                                        + " account_version=?",
                                    encoded,
                                    actor.id(),
                                    original,
                                    snapshot.get("accountVersion"));
                    if (changed != 1) {
                        throw new ApiException(409, "VERSION_CONFLICT", "密码已变更，请重新登录");
                    }
                    revokeSessions(actor.id());
                    auditAccount(actor.id(), actor.name(), "ACCOUNT_PASSWORD_CHANGED");
                });
        clearCookies(response);
    }

    String encodeNewPassword(Map<String, Object> body, String original) {
        String next = Inputs.text(body, "newPassword", 128, true);
        if (next.length() < 10 || next.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.bad("新密码至少10位且UTF-8长度不超过72字节");
        }
        if (matches(next, original)) {
            throw ApiException.bad("新密码不能与原密码相同");
        }
        return encode(next);
    }

    void requireActiveSession(Actor actor) {
        if (db.find(
                        "SELECT id FROM auth_session WHERE id=? AND user_id=?"
                                + " AND revoked_at IS NULL AND expires_at>? FOR UPDATE",
                        actor.sessionId(),
                        actor.id(),
                        Timestamp.from(Instant.now()))
                == null) {
            throw new ApiException(401, "UNAUTHORIZED", "请重新登录");
        }
    }

    void revokeSessions(String userId) {
        db.update(
                "UPDATE auth_session SET revoked_at=?,last_seen_at=NULL"
                        + " WHERE user_id=? AND revoked_at IS NULL",
                Timestamp.from(Instant.now()),
                userId);
        db.update(
                "UPDATE dispatch_manager SET position_building_id=NULL,position_floor_no=NULL,"
                        + " position_updated_at=NULL,version=version+1 WHERE user_id=?"
                        + " AND position_updated_at IS NOT NULL",
                userId);
        events.publishEvent(new SessionsRevoked(userId, null));
    }

    void auditAccount(String userId, String name, String action) {
        db.update(
                "INSERT INTO"
                    + " audit_event(operator_id,operator_name,action,business_type,business_id,note,changes,created_at)"
                    + " VALUES(?,?,?,'ACCOUNT',?,'','{}',?)",
                userId,
                name,
                action,
                userId,
                Timestamp.from(Instant.now()));
    }

    void clearCookies(HttpServletResponse response) {
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
        return raw.getBytes(StandardCharsets.UTF_8).length <= 72 && passwords.matches(raw, encoded);
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
        RANDOM.nextBytes(b);
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
        String type = clientType(b, request);
        boolean byPhone =
                b.containsKey("identifierType")
                        && Inputs.choice(b, "identifierType", "USERNAME", "PHONE").equals("PHONE");
        String identifier = Inputs.text(b, "username", 64, true);
        String password = Inputs.text(b, "password", 128, true);
        var u =
                byPhone
                        ? db.find(
                                "SELECT * FROM sys_user WHERE login_phone=? AND enabled=TRUE"
                                        + " AND login_phone_verified_at IS NOT NULL",
                                Inputs.phone(b, "username"))
                        : db.find(
                                "SELECT * FROM sys_user WHERE username=? AND enabled=TRUE",
                                identifier);
        String attemptKey =
                Digests.sha256(
                        u == null
                                ? request.getRemoteAddr() + "\n" + identifier
                                : "account:" + u.get("id"));
        attempts.begin(attemptKey, Instant.now());
        if (u == null
                || !availableForClient(u, type)
                || !matches(password, u.get("passwordHash").toString())) {
            throw new ApiException(401, "LOGIN_FAILED", "账号或密码不正确");
        }
        // Password work is outside the transaction. Serialize session creation with password,
        // account and permission maintenance, then recheck the exact credential snapshot.
        return writes.execute(
                status -> {
                    var current =
                            db.one("SELECT * FROM sys_user WHERE id=? FOR UPDATE", u.get("id"));
                    if (!availableForClient(current, type)
                            || !Objects.equals(current.get("passwordHash"), u.get("passwordHash"))
                            || !Objects.equals(
                                    current.get("accountVersion"), u.get("accountVersion"))
                            || (byPhone
                                    && (!identifier.equals(current.get("loginPhone"))
                                            || current.get("loginPhoneVerifiedAt") == null))) {
                        throw new ApiException(401, "LOGIN_FAILED", "账号或密码已变更，请重新登录");
                    }
                    attempts.succeeded(attemptKey);
                    return createSession(current, type, response);
                });
    }

    boolean availableForClient(Map<String, Object> account, String clientType) {
        Object expiry = account.get("credentialExpiresAt");
        return Boolean.TRUE.equals(account.get("enabled"))
                && (!clientType.equals("WEB") || "MANAGER".equals(account.get("role")))
                && (expiry == null || Instant.parse(expiry.toString()).isAfter(Instant.now()));
    }

    String clientType(Map<String, Object> body, HttpServletRequest request) {
        String type = Inputs.choice(body, "clientType", "WEB", "MOBILE");
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
        return type;
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
                        "SELECT"
                            + " id,username,display_name,role,must_change_password,login_phone,login_phone_verified_at,recovery_code_hash"
                            + " FROM sys_user WHERE id=?",
                        a.id());
        String phone = (String) u.remove("loginPhone");
        Object verified = u.remove("loginPhoneVerifiedAt");
        Object recovery = u.remove("recoveryCodeHash");
        if (phone != null && verified != null) {
            u.put("phoneMasked", Inputs.mask(phone));
        }
        u.put("recoveryCodeConfigured", recovery != null);
        u.put("aiConfigured", aiConfigured);
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
