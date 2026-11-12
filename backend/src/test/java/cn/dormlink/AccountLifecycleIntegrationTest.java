package cn.dormlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import cn.dormlink.identity.AuthService;
import cn.dormlink.shared.jdbc.Db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        OpenGaussTestDatabase.configure(r, "account");
    }

    @LocalServerPort int port;
    @MockitoSpyBean Db db;
    @Autowired AuthService auth;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient client = HttpClient.newHttpClient();

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
}
