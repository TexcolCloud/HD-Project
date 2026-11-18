package cn.dormlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import cn.dormlink.identity.AuthService;
import cn.dormlink.identity.SmsDelivery;
import cn.dormlink.shared.crypto.Digests;
import cn.dormlink.shared.jdbc.Db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ActiveProfiles(resolver = OpenGaussTestDatabase.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:account_lifecycle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "app.seed=true",
            "app.dispatch.automatic=false",
            "app.cookie-secure=false",
            "app.files=target/account-lifecycle-uploads"
        })
class AccountLifecycleIntegrationTest {
    private static final String PASSWORD = "Account-Test-12345";
    private static final String NEW_PASSWORD = "Account-Test-67890";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        OpenGaussTestDatabase.configure(r, "account");
    }

    @LocalServerPort int port;
    @MockitoSpyBean Db db;
    @MockitoBean SmsDelivery sms;
    @Autowired AuthService auth;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient client = HttpClient.newHttpClient();
    final Map<String, String> deliveries = new ConcurrentHashMap<>();
    private final Set<String> fixturePhones = new HashSet<>();

    @BeforeEach
    void prepareSmsFixtures() {
        // This suite owns its database. Keep per-number and per-account windows intact while
        // retiring the shared windows left by earlier tests or Maven invocations.
        db.update(
                "UPDATE auth_verification_limit SET expires_at=? WHERE bucket_key IN (?,?,?,?)",
                expiredWindow(),
                Digests.sha256("SMS_GLOBAL"),
                Digests.sha256("SMS_SOURCE:127.0.0.1"),
                Digests.sha256("VERIFY_GLOBAL"),
                Digests.sha256("VERIFY_SOURCE:127.0.0.1"));
        doAnswer(
                        call -> {
                            deliveries.put(
                                    call.<String>getArgument(0) + "/" + call.<String>getArgument(1),
                                    call.getArgument(2));
                            return null;
                        })
                .when(sms)
                .enqueue(anyString(), anyString(), anyString());
    }

    HttpResponse<String> request(String token, String method, String path, Object body)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", UUID.randomUUID().toString());
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    String login(String username, String password) throws Exception {
        var r =
                request(
                        null,
                        "POST",
                        "/auth/login",
                        Map.of("username", username, "password", password, "clientType", "MOBILE"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body()).path("data").path("token").asText();
    }

    @Test
    void anonymousRecoveryAcceptsWellFormedUnboundNumberWithoutRevealingAccountState()
            throws Exception {
        var response =
                request(
                        null,
                        "POST",
                        "/auth/password-recovery/challenges",
                        Map.of("phone", unusedPhone(), "clientType", "MOBILE"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("data").path("challengeId").asText())
                .isNotBlank();
        assertThat(deliveries).isEmpty();
    }

    @Test
    void temporaryStudentActivatesThePreprovisionedAccountAndSignsInByPhone() throws Exception {
        Account student = account("STUDENT", true);
        String phone = unusedPhone();
        assertThat(
                        post(
                                        student.token(),
                                        "/auth/phone/challenges",
                                        Map.of(
                                                "purpose",
                                                "BIND",
                                                "phone",
                                                phone,
                                                "currentPassword",
                                                PASSWORD))
                                .statusCode())
                .isEqualTo(403);
        Code code = challenge(student.token(), "ACTIVATE", phone, phone);
        var payload =
                Map.of("challengeId", code.id(), "code", code.value(), "newPassword", NEW_PASSWORD);
        assertThat(data(post(student.token(), "/auth/activate", payload)).has("recoveryCode"))
                .isFalse();
        assertThat(request(student.token(), "GET", "/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(post(student.token(), "/auth/activate", payload).statusCode()).isEqualTo(401);
        String token = loginByPhone(phone, NEW_PASSWORD);
        var me = data(request(token, "GET", "/auth/me", null));
        assertThat(me.path("id").asText()).isEqualTo(student.id());
        assertThat(me.path("phoneMasked").asText()).isEqualTo("139****" + phone.substring(7));
        assertThat(me.has("phone")).isFalse();
        assertThat(me.has("recoveryCodeConfigured")).isFalse();
        assertThat(login(student.username(), NEW_PASSWORD)).isNotBlank();

        String csrf = data(request(null, "GET", "/auth/csrf", null)).path("token").asText();
        var web =
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", "HD_CSRF=" + csrf)
                        .header("X-XSRF-TOKEN", csrf)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "clientType",
                                                        "WEB",
                                                        "username",
                                                        phone,
                                                        "identifierType",
                                                        "PHONE",
                                                        "password",
                                                        NEW_PASSWORD))))
                        .build();
        assertThat(client.send(web, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    @Test
    void managerRecoveryConsumesOfflineCodeAndRevokesSessions() throws Exception {
        Account manager = account("MANAGER", true);
        String phone = unusedPhone();
        Code activation = challenge(manager.token(), "ACTIVATE", phone, phone);
        String recovery =
                data(post(
                                manager.token(),
                                "/auth/activate",
                                Map.of(
                                        "challengeId",
                                        activation.id(),
                                        "code",
                                        activation.value(),
                                        "newPassword",
                                        NEW_PASSWORD)))
                        .path("recoveryCode")
                        .asText();
        assertThat(recovery).isNotBlank();
        String token = loginByPhone(phone, NEW_PASSWORD);
        assertThat(
                        data(request(token, "GET", "/auth/me", null))
                                .path("recoveryCodeConfigured")
                                .asBoolean())
                .isTrue();
        expireCooldown(phone);
        Code code = recoveryChallenge(phone);
        var verify = recoverVerify(code, "MOBILE");
        assertThat(verify.path("recoveryCodeRequired").asBoolean()).isTrue();
        String reset = verify.path("resetToken").asText();
        var incomplete =
                Map.of("clientType", "MOBILE", "resetToken", reset, "newPassword", PASSWORD);
        assertThat(post(null, "/auth/password-recovery/complete", incomplete).statusCode())
                .isEqualTo(400);
        assertThat(
                        db.one(
                                        "SELECT attempts FROM auth_password_reset WHERE"
                                                + " token_hash=?",
                                        Digests.sha256(reset))
                                .get("attempts"))
                .isEqualTo(1);
        String path = "/auth/password-recovery/complete";
        var completed =
                Map.of(
                        "clientType",
                        "MOBILE",
                        "resetToken",
                        reset,
                        "newPassword",
                        PASSWORD,
                        "recoveryCode",
                        recovery);
        assertThat(data(post(null, path, completed)).isNull()).isTrue();
        assertThat(post(null, path, completed).statusCode()).isEqualTo(400);
        assertThat(request(token, "GET", "/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(
                        db.one("SELECT recovery_code_hash FROM sys_user WHERE id=?", manager.id())
                                .get("recoveryCodeHash"))
                .isNull();

        String nextToken = loginByPhone(phone, PASSWORD);
        expireCooldown(phone);
        Code replacement = challenge(nextToken, "REPLACE_RECOVERY_CODE", null, phone);
        String nextRecovery =
                data(post(nextToken, "/auth/recovery-code/replace", codeBody(replacement)))
                        .path("recoveryCode")
                        .asText();
        assertThat(nextRecovery).isNotBlank().isNotEqualTo(recovery);
        expireCooldown(phone);
        String nextReset =
                recoverVerify(recoveryChallenge(phone), "MOBILE").path("resetToken").asText();
        assertThat(
                        post(
                                        null,
                                        path,
                                        Map.of(
                                                "clientType",
                                                "MOBILE",
                                                "resetToken",
                                                nextReset,
                                                "newPassword",
                                                NEW_PASSWORD,
                                                "recoveryCode",
                                                recovery))
                                .statusCode())
                .isEqualTo(400);
    }

    @Test
    void dualVerificationChangesPhoneAtomicallyAndKeepsTheAccountIdentity() throws Exception {
        Account student = account("STUDENT", false);
        String oldPhone = unusedPhone(), newPhone = unusedPhone();
        Code binding = challenge(student.token(), "BIND", oldPhone, oldPhone);
        data(post(student.token(), "/auth/phone/bind", codeBody(binding)));
        expireCooldown(oldPhone);
        Code old = challenge(student.token(), "CHANGE_PHONE_OLD", null, oldPhone);
        Code next = challenge(student.token(), "CHANGE_PHONE_NEW", newPhone, newPhone);
        String anotherSession = login(student.username(), PASSWORD);
        var update =
                Map.of(
                        "oldChallengeId",
                        old.id(),
                        "oldCode",
                        old.value(),
                        "newChallengeId",
                        next.id(),
                        "newCode",
                        next.value());
        assertThat(post(anotherSession, "/auth/phone/change", update).statusCode()).isEqualTo(400);
        data(post(student.token(), "/auth/phone/change", update));
        assertThat(request(student.token(), "GET", "/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(request(anotherSession, "GET", "/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(post(null, "/auth/login", phoneLogin(oldPhone, PASSWORD)).statusCode())
                .isEqualTo(401);
        var me = data(request(loginByPhone(newPhone, PASSWORD), "GET", "/auth/me", null));
        assertThat(me.path("id").asText()).isEqualTo(student.id());
    }

    @Test
    void onlyOneAccountCanBindTheSameNumberAndFailedAttemptsAreDurable() throws Exception {
        Account one = account("STUDENT", false), two = account("MANAGER", false);
        String phone = unusedPhone();
        Code first = challenge(one.token(), "BIND", phone, phone);
        assertThat(
                        post(
                                        two.token(),
                                        "/auth/phone/challenges",
                                        Map.of(
                                                "purpose",
                                                "BIND",
                                                "phone",
                                                phone,
                                                "currentPassword",
                                                PASSWORD))
                                .statusCode())
                .isEqualTo(429);
        expireCooldown(phone);
        Code second = challenge(two.token(), "BIND", phone, phone);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> post(one.token(), "/auth/phone/bind", codeBody(first)));
            var b = executor.submit(() -> post(two.token(), "/auth/phone/bind", codeBody(second)));
            assertThat(
                            java.util.List.of(
                                    a.get(10, TimeUnit.SECONDS).statusCode(),
                                    b.get(10, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(db.count("SELECT COUNT(*) FROM sys_user WHERE login_phone=?", phone))
                .isEqualTo(1);

        Account third = account("STUDENT", false);
        String limitPhone = unusedPhone();
        Code limited = challenge(third.token(), "BIND", limitPhone, limitPhone);
        String incorrect = "000000".equals(limited.value()) ? "000001" : "000000";
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(
                            post(
                                            third.token(),
                                            "/auth/phone/bind",
                                            Map.of("challengeId", limited.id(), "code", incorrect))
                                    .statusCode())
                    .isEqualTo(400);
        }
        assertThat(
                        db.one("SELECT attempts FROM auth_phone_challenge WHERE id=?", limited.id())
                                .get("attempts"))
                .isEqualTo(5);
        assertThat(post(third.token(), "/auth/phone/bind", codeBody(limited)).statusCode())
                .isEqualTo(400);
    }

    @Test
    void newStudentDoesNotReceiveSharedDemoPassword() throws Exception {
        String manager = login("manager1", "Demo@123456");
        String no = "QA-ACCOUNT-" + UUID.randomUUID().toString().substring(0, 8);
        var created =
                request(
                        manager,
                        "POST",
                        "/students",
                        Map.of(
                                "studentNo",
                                no,
                                "name",
                                "虚构账号验收",
                                "college",
                                "测试学院",
                                "phone",
                                "13800000000"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        var login =
                request(
                        null,
                        "POST",
                        "/auth/login",
                        Map.of("username", no, "password", "Demo@123456", "clientType", "MOBILE"));
        assertThat(login.statusCode()).isEqualTo(401);
    }

    @Test
    void repeatedFailuresAreTemporarilyThrottled() throws Exception {
        String no = "QA-MISSING-" + UUID.randomUUID();
        HttpResponse<String> response = null;
        for (int i = 0; i < 6; i++)
            response =
                    request(
                            null,
                            "POST",
                            "/auth/login",
                            Map.of(
                                    "username",
                                    no,
                                    "password",
                                    "bad-password",
                                    "clientType",
                                    "MOBILE"));
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("LOGIN_THROTTLED");
    }

    @Test
    void temporaryCredentialIsRestrictedUntilChangedAndThenRevoked() throws Exception {
        String no = "QA-TEMP-" + UUID.randomUUID();
        String id =
                db.insert(
                        "INSERT INTO sys_user(username,password_hash,display_name,role,enabled)"
                                + " VALUES(?,?,?,?,?)",
                        no,
                        auth.encode("Temp-Initial-12345"),
                        "虚构临时账号",
                        "MANAGER",
                        true);
        db.update("UPDATE sys_user SET must_change_password=TRUE WHERE id=?", id);
        String token = login(no, "Temp-Initial-12345");
        var profile = request(token, "GET", "/auth/me", null);
        assertThat(
                        json.readTree(profile.body())
                                .path("data")
                                .path("mustChangePassword")
                                .asBoolean())
                .isTrue();
        assertThat(request(token, "GET", "/buildings", null).body())
                .contains("PASSWORD_CHANGE_REQUIRED");
        assertThat(
                        request(
                                        token,
                                        "POST",
                                        "/auth/change-password",
                                        Map.of(
                                                "oldPassword",
                                                "Temp-Initial-12345",
                                                "newPassword",
                                                "Temp-Initial-12345"))
                                .statusCode())
                .isEqualTo(400);
        assertThat(
                        request(
                                        token,
                                        "POST",
                                        "/auth/change-password",
                                        Map.of(
                                                "oldPassword",
                                                "Temp-Initial-12345",
                                                "newPassword",
                                                "Changed-Account-12345"))
                                .statusCode())
                .isEqualTo(200);
        assertThat(request(token, "GET", "/auth/me", null).statusCode()).isEqualTo(401);
        String changed = login(no, "Changed-Account-12345");
        assertThat(
                        json.readTree(request(changed, "GET", "/auth/me", null).body())
                                .path("data")
                                .path("mustChangePassword")
                                .asBoolean())
                .isFalse();
    }

    @Test
    void oldPasswordSnapshotCannotCreateSessionAfterConcurrentPasswordChange() throws Exception {
        String no = "QA-RACE-" + UUID.randomUUID();
        db.insert(
                "INSERT INTO sys_user(username,password_hash,display_name,role,enabled)"
                        + " VALUES(?,?,?,?,?)",
                no,
                auth.encode("Race-Initial-12345"),
                "虚构竞争账号",
                "MANAGER",
                true);
        String token = login(no, "Race-Initial-12345");
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        doAnswer(
                        call -> {
                            Object result = call.callRealMethod();
                            if (first.getAndSet(false)) {
                                read.countDown();
                                if (!release.await(20, TimeUnit.SECONDS))
                                    throw new AssertionError("Login snapshot was not released");
                            }
                            return result;
                        })
                .when(db)
                .find(eq("SELECT * FROM sys_user WHERE username=? AND enabled=TRUE"), eq(no));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending =
                    executor.submit(
                            () ->
                                    request(
                                            null,
                                            "POST",
                                            "/auth/login",
                                            Map.of(
                                                    "username",
                                                    no,
                                                    "password",
                                                    "Race-Initial-12345",
                                                    "clientType",
                                                    "MOBILE")));
            assertThat(read.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(
                            request(
                                            token,
                                            "POST",
                                            "/auth/change-password",
                                            Map.of(
                                                    "oldPassword",
                                                    "Race-Initial-12345",
                                                    "newPassword",
                                                    "Race-Changed-12345"))
                                    .statusCode())
                    .isEqualTo(200);
            release.countDown();
            assertThat(pending.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
            login(no, "Race-Changed-12345");
        } finally {
            release.countDown();
            executor.shutdownNow();
            reset(db);
        }
    }

    private Account account(String role, boolean temporary) throws Exception {
        String username = "QA-IDENTITY-" + UUID.randomUUID();
        String id =
                db.insert(
                        "INSERT INTO"
                            + " sys_user(username,password_hash,display_name,role,enabled,must_change_password,credential_expires_at)"
                            + " VALUES(?,?,?,?,?,?,?)",
                        username,
                        auth.encode(PASSWORD),
                        "虚构测试账号",
                        role,
                        true,
                        temporary,
                        temporary ? Timestamp.from(Instant.now().plusSeconds(3600)) : null);
        return new Account(id, username, login(username, PASSWORD));
    }

    private Code challenge(String token, String purpose, String enteredPhone, String destination)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("purpose", purpose);
        body.put("currentPassword", PASSWORD);
        if (enteredPhone != null) {
            body.put("phone", enteredPhone);
        }
        String id = data(post(token, "/auth/phone/challenges", body)).path("challengeId").asText();
        return recordedCode(id, destination, purpose);
    }

    private Code recoveryChallenge(String phone) throws Exception {
        String id =
                data(post(
                                null,
                                "/auth/password-recovery/challenges",
                                Map.of("clientType", "MOBILE", "phone", phone)))
                        .path("challengeId")
                        .asText();
        return recordedCode(id, phone, "PASSWORD_RECOVERY");
    }

    private Code recordedCode(String id, String phone, String purpose) {
        assertThat(id).isNotBlank();
        String code = deliveries.get(phone + "/" + purpose);
        assertThat(code).matches("[0-9]{6}");
        return new Code(id, code);
    }

    private JsonNode recoverVerify(Code code, String type) throws Exception {
        return data(
                post(
                        null,
                        "/auth/password-recovery/verify",
                        Map.of(
                                "clientType",
                                type,
                                "challengeId",
                                code.id(),
                                "code",
                                code.value())));
    }

    private void expireCooldown(String phone) {
        db.update(
                "UPDATE auth_verification_limit SET expires_at=? WHERE bucket_key=?",
                expiredWindow(),
                Digests.sha256("SMS_RESEND:" + phone));
    }

    private String unusedPhone() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String phone = "139" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
            if (fixturePhones.add(phone)
                    && db.count("SELECT COUNT(*) FROM sys_user WHERE login_phone=?", phone) == 0
                    && db.count(
                                    "SELECT COUNT(*) FROM auth_verification_limit"
                                            + " WHERE bucket_key IN (?,?,?)",
                                    Digests.sha256("SMS_PHONE:" + phone),
                                    Digests.sha256("SMS_RESEND:" + phone),
                                    Digests.sha256("VERIFY_PHONE:" + phone))
                            == 0) {
                return phone;
            }
        }
        throw new AssertionError("Unable to allocate an unused test phone");
    }

    private static Timestamp expiredWindow() {
        return Timestamp.from(Instant.now().minusSeconds(1));
    }

    private HttpResponse<String> post(String token, String path, Object requestBody)
            throws Exception {
        return request(token, "POST", path, requestBody);
    }

    private JsonNode data(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    private String loginByPhone(String phone, String password) throws Exception {
        return data(post(null, "/auth/login", phoneLogin(phone, password))).path("token").asText();
    }

    private static Map<String, String> phoneLogin(String phone, String password) {
        return Map.of(
                "clientType",
                "MOBILE",
                "username",
                phone,
                "identifierType",
                "PHONE",
                "password",
                password);
    }

    private static Map<String, String> codeBody(Code code) {
        return Map.of("challengeId", code.id(), "code", code.value());
    }

    private record Account(String id, String username, String token) {}

    private record Code(String id, String value) {}
}
