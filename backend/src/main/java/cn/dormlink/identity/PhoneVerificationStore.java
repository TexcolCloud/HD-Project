package cn.dormlink.identity;

import cn.dormlink.shared.crypto.Digests;
import cn.dormlink.shared.jdbc.Db;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
class PhoneVerificationStore {
    static final int MAX_ATTEMPTS = 5;
    private static final long LIFETIME_SECONDS = 300;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Db db;
    private final AuthService auth;
    private final SecretKeySpec signingKey;

    PhoneVerificationStore(Db db, AuthService auth, @Value("${APP_SMS_SIGNING_KEY:}") String key) {
        this.db = db;
        this.auth = auth;
        signingKey =
                new SecretKeySpec(
                        (key.isBlank() ? auth.random() : key).getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256");
    }

    Issued issue(
            String userId,
            String sessionId,
            Integer version,
            String purpose,
            String phone,
            String clientType,
            boolean graphic) {
        String id = auth.random();
        String code =
                graphic
                        ? CaptchaRenderer.newCode()
                        : String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        Instant now = Instant.now();
        if (userId != null) {
            db.update(
                    "UPDATE auth_phone_challenge SET consumed_at=? WHERE user_id=? AND purpose=?"
                            + " AND consumed_at IS NULL AND expires_at>?",
                    Timestamp.from(now),
                    userId,
                    purpose,
                    Timestamp.from(now));
        }
        db.update(
                "INSERT INTO auth_phone_challenge(id,user_id,session_id,account_version,purpose,"
                        + "phone,client_type,code_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id,
                userId,
                sessionId,
                version,
                purpose,
                userId == null ? null : phone,
                clientType,
                codeHash(id, code, graphic),
                Timestamp.from(now.plusSeconds(LIFETIME_SECONDS)));
        return new Issued(id, userId == null && !graphic ? null : code);
    }

    Challenge challenge(String id, boolean lock) {
        return challenge(
                id,
                db.find("SELECT * FROM auth_phone_challenge WHERE id=?" + lockClause(lock), id));
    }

    Challenge sessionChallenge(String id, String userId, String sessionId, boolean lock) {
        return challenge(
                id,
                db.find(
                        "SELECT * FROM auth_phone_challenge WHERE id=? AND user_id=? AND"
                                + " session_id=?"
                                + lockClause(lock),
                        id,
                        userId,
                        sessionId));
    }

    private Challenge challenge(String id, Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return new Challenge(
                id,
                value(row, "userId"),
                value(row, "sessionId"),
                Db.number(row, "accountVersion"),
                value(row, "purpose"),
                value(row, "phone"),
                value(row, "clientType"),
                value(row, "codeHash"),
                active(row));
    }

    boolean accepts(Challenge challenge, String code, boolean graphic) {
        if (!challenge.active()) {
            return false;
        }
        db.update("UPDATE auth_phone_challenge SET attempts=attempts+1 WHERE id=?", challenge.id());
        return secureEquals(challenge.codeHash(), codeHash(challenge.id(), code, graphic));
    }

    void consume(Challenge challenge) {
        db.update(
                "UPDATE auth_phone_challenge SET consumed_at=? WHERE id=?",
                Timestamp.from(Instant.now()),
                challenge.id());
    }

    String resetToken(Challenge challenge) {
        String token = auth.random();
        Instant now = Instant.now();
        db.update(
                "UPDATE auth_password_reset SET consumed_at=? WHERE user_id=?"
                        + " AND consumed_at IS NULL AND expires_at>?",
                Timestamp.from(now),
                challenge.userId(),
                Timestamp.from(now));
        db.update(
                "INSERT INTO auth_password_reset(token_hash,user_id,account_version,phone,"
                        + "client_type,expires_at) VALUES(?,?,?,?,?,?)",
                Digests.sha256(token),
                challenge.userId(),
                challenge.version(),
                challenge.phone(),
                challenge.clientType(),
                Timestamp.from(now.plusSeconds(LIFETIME_SECONDS)));
        return token;
    }

    Reset reset(String token, boolean lock) {
        String hash = Digests.sha256(token);
        var row =
                db.find(
                        "SELECT * FROM auth_password_reset WHERE token_hash=?" + lockClause(lock),
                        hash);
        if (row == null) {
            return null;
        }
        return new Reset(
                hash,
                value(row, "userId"),
                Db.number(row, "accountVersion"),
                value(row, "phone"),
                value(row, "clientType"),
                active(row));
    }

    void reject(Reset reset) {
        db.update(
                "UPDATE auth_password_reset SET attempts=attempts+1 WHERE token_hash=?",
                reset.hash());
    }

    void consume(Reset reset) {
        db.update(
                "UPDATE auth_password_reset SET consumed_at=? WHERE token_hash=?",
                Timestamp.from(Instant.now()),
                reset.hash());
    }

    static boolean secureEquals(String expected, String supplied) {
        return expected != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8));
    }

    private String codeHash(String id, String code, boolean graphic) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal(
                                    (id
                                                    + (graphic ? ":graphic:" : ":")
                                                    + code.trim().toUpperCase(Locale.ROOT))
                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot authenticate verification code", e);
        }
    }

    private static String lockClause(boolean lock) {
        return lock ? " FOR UPDATE" : "";
    }

    private static String value(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }

    private static boolean active(Map<String, Object> row) {
        return row.get("consumedAt") == null
                && Db.number(row, "attempts") < MAX_ATTEMPTS
                && Instant.parse(row.get("expiresAt").toString()).isAfter(Instant.now());
    }

    record Issued(String id, String code) {}

    record Challenge(
            String id,
            String userId,
            String sessionId,
            long version,
            String purpose,
            String phone,
            String clientType,
            String codeHash,
            boolean active) {}

    record Reset(
            String hash,
            String userId,
            long version,
            String phone,
            String clientType,
            boolean active) {}
}
