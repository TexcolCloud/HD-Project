package cn.dormlink.identity;

import cn.dormlink.identity.PhoneVerificationStore.Challenge;
import cn.dormlink.identity.PhoneVerificationStore.Reset;
import cn.dormlink.shared.api.ApiException;
import cn.dormlink.shared.api.Inputs;
import cn.dormlink.shared.crypto.Digests;
import cn.dormlink.shared.jdbc.Db;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@Service
class AccountSecurityService {
    private final AuthService auth;
    private final Db db;
    private final PhoneVerificationStore verification;
    private final VerificationLimits limits;
    private final SmsDelivery sms;
    private final CaptchaRenderer captcha;
    private final TransactionTemplate writes;

    AccountSecurityService(
            AuthService auth,
            Db db,
            PhoneVerificationStore verification,
            VerificationLimits limits,
            SmsDelivery sms,
            CaptchaRenderer captcha,
            PlatformTransactionManager transactions) {
        this.auth = auth;
        this.db = db;
        this.verification = verification;
        this.limits = limits;
        this.sms = sms;
        this.captcha = captcha;
        writes = new TransactionTemplate(transactions);
    }

    Map<String, String> requestPhone(Map<String, Object> body, HttpServletRequest request) {
        Purpose purpose = Purpose.from(body);
        Actor actor = auth.current();
        var snapshot = account(actor.id(), false);
        requireAccount(snapshot, actor.clientType());
        String proposed =
                purpose == Purpose.ACTIVATE
                                || purpose == Purpose.BIND
                                || purpose == Purpose.CHANGE_PHONE_NEW
                        ? phone(body)
                        : null;
        String destination = destination(snapshot, purpose, proposed);
        String source = request.getRemoteAddr();
        if (purpose != Purpose.ACTIVATE) {
            if (!limits.verify(source, "current-password:" + actor.id(), null, actor.id())) {
                throw throttled();
            }
            if (!auth.matches(
                    Inputs.text(body, "currentPassword", 128, true),
                    snapshot.get("passwordHash").toString())) {
                throw new ApiException(403, "CURRENT_PASSWORD_INVALID", "当前密码不正确");
            }
        }
        boolean graphic = !sms.configured();
        if (!limits.issue(source, destination, actor.id(), graphic)) {
            throw throttled();
        }
        var issued =
                writes.execute(
                        status -> {
                            var current = account(actor.id(), true);
                            requireAccount(current, actor.clientType());
                            auth.requireActiveSession(actor);
                            if (!Objects.equals(
                                    current.get("accountVersion"),
                                    snapshot.get("accountVersion"))) {
                                throw new ApiException(409, "VERSION_CONFLICT", "账号信息已变化，请重新操作");
                            }
                            destination(current, purpose, proposed);
                            return verification.issue(
                                    actor.id(),
                                    actor.sessionId(),
                                    version(current),
                                    purpose.name(),
                                    destination,
                                    actor.clientType(),
                                    graphic);
                        });
        return deliver(issued, destination, purpose, graphic);
    }

    Map<String, String> submitPhone(
            Map<String, Object> body, HttpServletRequest request, Purpose purpose) {
        Actor actor = auth.current();
        Submission submission = submission(body, "challengeId", "code");
        Challenge hint = sessionChallenge(actor, submission.id(), false);
        if (!chargeAttempt(request, submission.id(), hint)) {
            throw invalidVerification();
        }
        var snapshot = account(actor.id(), false);
        requireAccount(snapshot, actor.clientType());
        String passwordHash =
                purpose == Purpose.ACTIVATE
                        ? auth.encodeNewPassword(body, snapshot.get("passwordHash").toString())
                        : null;
        return writePhone(
                () -> {
                    var current = lockedAccount(actor);
                    Challenge challenge =
                            accountChallenge(actor, current, purpose, submission.id());
                    if (challenge == null
                            || !verification.accepts(
                                    challenge, submission.code(), !sms.configured())) {
                        return null;
                    }
                    String target = destination(current, purpose, challenge.phone());
                    if (!Objects.equals(target, challenge.phone())) {
                        return null;
                    }
                    var result = updateAccount(current, purpose, target, passwordHash);
                    verification.consume(challenge);
                    return result;
                });
    }

    Map<String, String> changePhone(Map<String, Object> body, HttpServletRequest request) {
        Actor actor = auth.current();
        boolean graphic = !sms.configured();
        Submission old = graphic ? null : submission(body, "oldChallengeId", "oldCode");
        Submission next = submission(body, "newChallengeId", "newCode");
        if (old != null && old.id().equals(next.id())) {
            throw invalidVerification();
        }
        Challenge oldHint = old == null ? null : sessionChallenge(actor, old.id(), false);
        Challenge nextHint = sessionChallenge(actor, next.id(), false);
        if ((old != null && !chargeAttempt(request, old.id(), oldHint))
                || !chargeAttempt(request, next.id(), nextHint)) {
            throw invalidVerification();
        }
        return writePhone(
                () -> {
                    var current = lockedAccount(actor);
                    Challenge oldChallenge =
                            old == null
                                    ? null
                                    : accountChallenge(
                                            actor, current, Purpose.CHANGE_PHONE_OLD, old.id());
                    Challenge nextChallenge =
                            accountChallenge(actor, current, Purpose.CHANGE_PHONE_NEW, next.id());
                    if ((old != null && oldChallenge == null) || nextChallenge == null) {
                        return null;
                    }
                    boolean oldVerified =
                            old == null || verification.accepts(oldChallenge, old.code(), false);
                    boolean newVerified = verification.accepts(nextChallenge, next.code(), graphic);
                    if (!oldVerified || !newVerified) {
                        return null;
                    }
                    String bound = destination(current, Purpose.CHANGE_PHONE_OLD, null);
                    String target =
                            destination(current, Purpose.CHANGE_PHONE_NEW, nextChallenge.phone());
                    if (oldChallenge != null && !bound.equals(oldChallenge.phone())) {
                        return null;
                    }
                    var result = updateAccount(current, Purpose.CHANGE_PHONE_NEW, target, null);
                    if (oldChallenge != null) {
                        verification.consume(oldChallenge);
                    }
                    verification.consume(nextChallenge);
                    return result;
                });
    }

    Map<String, String> requestRecovery(Map<String, Object> body, HttpServletRequest request) {
        String type = auth.clientType(body, request);
        String phone = phone(body);
        var candidate = db.find("SELECT * FROM sys_user WHERE login_phone=?", phone);
        boolean graphic = !sms.configured();
        if (!limits.issue(
                request.getRemoteAddr(),
                phone,
                candidate == null ? null : userId(candidate),
                graphic)) {
            return deliver(
                    new PhoneVerificationStore.Issued(
                            auth.random(), graphic ? CaptchaRenderer.newCode() : null),
                    phone,
                    Purpose.PASSWORD_RECOVERY,
                    graphic);
        }
        var issued =
                writes.execute(
                        status -> {
                            var current =
                                    candidate == null ? null : account(userId(candidate), true);
                            boolean eligible = recoverable(current, type, phone);
                            return verification.issue(
                                    eligible ? userId(current) : null,
                                    null,
                                    eligible ? version(current) : null,
                                    Purpose.PASSWORD_RECOVERY.name(),
                                    phone,
                                    type,
                                    graphic);
                        });
        return deliver(issued, phone, Purpose.PASSWORD_RECOVERY, graphic);
    }

    private Map<String, String> deliver(
            PhoneVerificationStore.Issued issued, String phone, Purpose purpose, boolean graphic) {
        if (graphic) {
            return Map.of(
                    "challengeId",
                    issued.id(),
                    "delivery",
                    "GRAPHIC",
                    "captchaImage",
                    captcha.render(issued.code()));
        }
        if (issued.code() != null) {
            sms.enqueue(phone, purpose.name(), issued.code());
        }
        return Map.of("challengeId", issued.id(), "delivery", "SMS");
    }

    Map<String, Object> verifyRecovery(Map<String, Object> body, HttpServletRequest request) {
        String type = auth.clientType(body, request);
        Submission submission = submission(body, "challengeId", "code");
        Challenge hint = verification.challenge(submission.id(), false);
        boolean graphic = !sms.configured();
        if (!chargeAttempt(request, submission.id(), hint)
                || hint == null
                || (hint.userId() == null && !graphic)) {
            throw invalidVerification();
        }
        var result =
                writes.execute(
                        status -> {
                            var account =
                                    hint.userId() == null ? null : account(hint.userId(), true);
                            Challenge challenge = verification.challenge(submission.id(), true);
                            if (challenge == null
                                    || !Purpose.PASSWORD_RECOVERY.name().equals(challenge.purpose())
                                    || !type.equals(challenge.clientType())
                                    || (hint.userId() != null
                                            && (!recoverable(account, type, challenge.phone())
                                                    || version(account) != challenge.version()))
                                    || !verification.accepts(
                                            challenge, submission.code(), graphic)) {
                                return null;
                            }
                            verification.consume(challenge);
                            // A solved anonymous graphic challenge gets an indistinguishable,
                            // unusable token. Only an eligible account receives a persisted reset.
                            return Map.<String, Object>of(
                                    "resetToken",
                                    hint.userId() == null
                                            ? auth.random()
                                            : verification.resetToken(challenge),
                                    "recoveryCodeRequired",
                                    true);
                        });
        if (result == null) {
            throw invalidVerification();
        }
        return result;
    }

    void completeRecovery(Map<String, Object> body, HttpServletRequest request) {
        String type = auth.clientType(body, request);
        String token = Inputs.text(body, "resetToken", 64, true);
        String recoveryCode = Inputs.text(body, "recoveryCode", 128, false);
        Reset hint = verification.reset(token, false);
        if (!limits.verify(
                        request.getRemoteAddr(),
                        "reset:" + token,
                        hint == null ? null : hint.phone(),
                        hint == null ? null : hint.userId())
                || hint == null
                || !hint.active()
                || !type.equals(hint.clientType())) {
            throw invalidVerification();
        }
        var snapshot = account(hint.userId(), false);
        if (!validReset(hint, snapshot, type)) {
            throw invalidVerification();
        }
        String recoveryHash = Digests.sha256(recoveryCode);
        String passwordHash =
                acceptsRecoveryCode(snapshot, recoveryHash)
                        ? auth.encodeNewPassword(body, snapshot.get("passwordHash").toString())
                        : null;
        boolean changed =
                Boolean.TRUE.equals(
                        writes.execute(
                                status -> {
                                    var current = account(hint.userId(), true);
                                    Reset reset = verification.reset(token, true);
                                    if (!validReset(reset, current, type)) {
                                        return false;
                                    }
                                    if (passwordHash == null
                                            || !acceptsRecoveryCode(current, recoveryHash)) {
                                        verification.reject(reset);
                                        return false;
                                    }
                                    db.update(
                                            "UPDATE sys_user SET"
                                                + " password_hash=?,recovery_code_hash=NULL,"
                                                + " must_change_password=FALSE,credential_expires_at=NULL,"
                                                + " account_version=account_version+1 WHERE id=?",
                                            passwordHash,
                                            userId(current));
                                    verification.consume(reset);
                                    auth.revokeSessions(userId(current));
                                    auth.auditAccount(
                                            userId(current),
                                            current.get("displayName").toString(),
                                            "ACCOUNT_PASSWORD_RECOVERED");
                                    return true;
                                }));
        if (!changed) {
            throw invalidVerification();
        }
    }

    private Map<String, String> updateAccount(
            Map<String, Object> current, Purpose purpose, String phone, String passwordHash) {
        String id = userId(current);
        String recoveryCode = auth.random();
        String recoveryHash = Digests.sha256(recoveryCode);
        Timestamp now = Timestamp.from(Instant.now());
        String action;
        switch (purpose) {
            case ACTIVATE -> {
                db.update(
                        "UPDATE sys_user SET"
                            + " login_phone=?,login_phone_verified_at=?,recovery_code_hash=?,"
                            + " password_hash=?,must_change_password=FALSE,credential_expires_at=NULL,"
                            + " account_version=account_version+1 WHERE id=?",
                        phone,
                        now,
                        recoveryHash,
                        passwordHash,
                        id);
                action = "ACCOUNT_ACTIVATED";
            }
            case BIND, CHANGE_PHONE_NEW -> {
                db.update(
                        "UPDATE sys_user SET"
                                + " login_phone=?,login_phone_verified_at=?,recovery_code_hash=?,"
                                + " account_version=account_version+1 WHERE id=?",
                        phone,
                        now,
                        recoveryHash,
                        id);
                action = purpose == Purpose.BIND ? "ACCOUNT_PHONE_BOUND" : "ACCOUNT_PHONE_CHANGED";
            }
            case REPLACE_RECOVERY_CODE -> {
                db.update(
                        "UPDATE sys_user SET recovery_code_hash=?,account_version=account_version+1"
                                + " WHERE id=?",
                        recoveryHash,
                        id);
                action = "ACCOUNT_RECOVERY_CODE_REPLACED";
            }
            default -> throw new IllegalArgumentException("Unsupported account transition");
        }
        if (purpose == Purpose.ACTIVATE || purpose == Purpose.CHANGE_PHONE_NEW) {
            auth.revokeSessions(id);
        }
        auth.auditAccount(id, current.get("displayName").toString(), action);
        return Map.of("recoveryCode", recoveryCode);
    }

    private Map<String, String> writePhone(Supplier<Map<String, String>> operation) {
        Map<String, String> result;
        try {
            result = writes.execute(status -> operation.get());
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "PHONE_UNAVAILABLE", "此手机号暂不可绑定");
        }
        if (result == null) {
            throw invalidVerification();
        }
        return result;
    }

    private Challenge accountChallenge(
            Actor actor, Map<String, Object> current, Purpose purpose, String id) {
        Challenge challenge = sessionChallenge(actor, id, true);
        if (challenge == null
                || !actor.clientType().equals(challenge.clientType())
                || !purpose.name().equals(challenge.purpose())
                || version(current) != challenge.version()) {
            return null;
        }
        return challenge;
    }

    private Challenge sessionChallenge(Actor actor, String id, boolean lock) {
        return verification.sessionChallenge(id, actor.id(), actor.sessionId(), lock);
    }

    private boolean chargeAttempt(HttpServletRequest request, String reference, Challenge hint) {
        return limits.verify(
                request.getRemoteAddr(),
                "challenge:" + reference,
                hint == null ? null : hint.phone(),
                hint == null ? null : hint.userId());
    }

    private static String destination(
            Map<String, Object> account, Purpose purpose, String proposed) {
        String bound = boundPhone(account);
        boolean temporary = temporary(account);
        if (purpose == Purpose.ACTIVATE) {
            if (!temporary || account.get("credentialExpiresAt") == null || bound != null) {
                throw new ApiException(403, "ACTIVATION_NOT_AVAILABLE", "此账号无法使用注册激活");
            }
            return proposed;
        }
        if (temporary) {
            throw new ApiException(403, "PASSWORD_CHANGE_REQUIRED", "请先修改临时密码后继续使用");
        }
        if (purpose == Purpose.BIND) {
            if (bound != null) {
                throw new ApiException(409, "PHONE_ALREADY_BOUND", "账号已绑定手机号，请使用换绑");
            }
            return proposed;
        }
        if (bound == null) {
            throw new ApiException(409, "PHONE_NOT_BOUND", "请先绑定手机号");
        }
        if (purpose == Purpose.CHANGE_PHONE_NEW) {
            if (bound.equals(proposed)) {
                throw ApiException.bad("新手机号不能与原手机号相同");
            }
            return proposed;
        }
        return bound;
    }

    private boolean validReset(Reset reset, Map<String, Object> account, String clientType) {
        return reset != null
                && reset.active()
                && clientType.equals(reset.clientType())
                && recoverable(account, clientType, reset.phone())
                && reset.version() == version(account);
    }

    private boolean recoverable(Map<String, Object> account, String clientType, String phone) {
        return account != null
                && auth.availableForClient(account, clientType)
                && !temporary(account)
                && phone != null
                && phone.equals(boundPhone(account))
                && account.get("recoveryCodeHash") != null;
    }

    private static boolean acceptsRecoveryCode(Map<String, Object> account, String recoveryHash) {
        return PhoneVerificationStore.secureEquals(
                (String) account.get("recoveryCodeHash"), recoveryHash);
    }

    private Map<String, Object> lockedAccount(Actor actor) {
        var account = account(actor.id(), true);
        requireAccount(account, actor.clientType());
        auth.requireActiveSession(actor);
        return account;
    }

    private Map<String, Object> account(String id, boolean lock) {
        return db.find("SELECT * FROM sys_user WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    private void requireAccount(Map<String, Object> account, String clientType) {
        if (account == null || !auth.availableForClient(account, clientType)) {
            throw new ApiException(401, "UNAUTHORIZED", "请重新登录");
        }
    }

    private static String phone(Map<String, Object> body) {
        return Inputs.phone(body, "phone");
    }

    private static Submission submission(Map<String, Object> body, String idKey, String codeKey) {
        String code = Inputs.text(body, codeKey, 6, true);
        if (code.length() < 4) {
            throw invalidVerification();
        }
        return new Submission(Inputs.text(body, idKey, 64, true), code);
    }

    private static boolean temporary(Map<String, Object> account) {
        return Boolean.TRUE.equals(account.get("mustChangePassword"));
    }

    private static int version(Map<String, Object> account) {
        return (int) Db.number(account, "accountVersion");
    }

    private static String userId(Map<String, Object> account) {
        return account.get("id").toString();
    }

    private static String boundPhone(Map<String, Object> account) {
        return account.get("loginPhoneVerifiedAt") == null
                ? null
                : (String) account.get("loginPhone");
    }

    private static ApiException invalidVerification() {
        return new ApiException(400, "VERIFICATION_INVALID", "验证凭证不正确或已失效，请重新验证");
    }

    private static ApiException throttled() {
        return new ApiException(429, "VERIFICATION_THROTTLED", "验证请求过多，请稍后再试");
    }

    enum Purpose {
        ACTIVATE,
        BIND,
        CHANGE_PHONE_OLD,
        CHANGE_PHONE_NEW,
        REPLACE_RECOVERY_CODE,
        PASSWORD_RECOVERY;

        static Purpose from(Map<String, Object> body) {
            return valueOf(
                    Inputs.choice(
                            body,
                            "purpose",
                            "ACTIVATE",
                            "BIND",
                            "CHANGE_PHONE_OLD",
                            "CHANGE_PHONE_NEW",
                            "REPLACE_RECOVERY_CODE"));
        }
    }

    private record Submission(String id, String code) {}
}
