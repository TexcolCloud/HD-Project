package cn.dormlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@org.springframework.test.context.ActiveProfiles("local")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'",
      "app.seed=true",
      "app.cookie-secure=false",
      "app.ai.api-key=",
      "app.files=target/test-uploads"
    })
class PlatformIntegrationTest {
  @LocalServerPort int port;
  final HttpClient http = HttpClient.newHttpClient();
  final tools.jackson.databind.json.JsonMapper json = new tools.jackson.databind.json.JsonMapper();

  HttpResponse<String> request(String token, String method, String path, Object body)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", java.util.UUID.randomUUID().toString());
    if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
    else
      b.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  String token(String name) throws Exception {
    var r =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\""
                            + name
                            + "\",\"password\":\"Demo@123456\",\"clientType\":\"MOBILE\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return json.readTree(r.body()).path("data").path("token").asText();
  }

  @Test
  void mobileLoginCreatesRevocablePersistentSession() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"student1\",\"password\":\"Demo@123456\",\"clientType\":\"MOBILE\"}"))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"token\"", "\"STUDENT\"");
  }

  @Test
  void studentCannotReadRoomManagement() throws Exception {
    var login =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"student1\",\"password\":\"Demo@123456\",\"clientType\":\"MOBILE\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String token =
        new tools.jackson.databind.json.JsonMapper()
            .readTree(login.body())
            .get("data")
            .get("token")
            .asText();
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/rooms"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(403);
  }

  @Test
  void disabledRoomFilterExcludesEnabledRooms() throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/v1/rooms?status=DISABLED"))
                .header("Authorization", "Bearer " + token("manager1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body()).path("data").path("total").asInt()).isZero();
  }

  @Test
  void logoutImmediatelyRevokesDatabaseSession() throws Exception {
    String t = token("student2");
    assertThat(request(t, "GET", "/auth/me", null).statusCode()).isEqualTo(200);
    assertThat(request(t, "POST", "/auth/logout", java.util.Map.of()).statusCode()).isEqualTo(200);
    assertThat(request(t, "GET", "/auth/me", null).statusCode()).isEqualTo(401);
  }

  @Test
  void repairStateMachinePreservesHistoryAndOwnership() throws Exception {
    String s = token("student1"),
        other = token("student2"),
        m = token("manager1"),
        mb = token("manager2");
    var payload =
        java.util.Map.of(
            "title",
            "测试灯具故障",
            "description",
            "台灯开关失灵",
            "category",
            "UTILITIES",
            "priority",
            "NORMAL",
            "contactName",
            "测试学生",
            "contactPhone",
            "13800000000",
            "reportImageIds",
            java.util.List.of());
    var create = request(s, "POST", "/repairs", payload);
    assertThat(create.statusCode()).isEqualTo(200);
    var order = json.readTree(create.body()).path("data");
    String id = order.path("id").asText();
    assertThat(request(other, "GET", "/repairs/" + id, null).statusCode()).isEqualTo(404);
    assertThat(request(mb, "GET", "/repairs/" + id, null).statusCode()).isEqualTo(404);
    assertThat(
            request(
                    s,
                    "POST",
                    "/repairs/" + id + "/confirm",
                    java.util.Map.of("expectedVersion", 0))
                .statusCode())
        .isEqualTo(409);
    for (String action : java.util.List.of("accept", "start", "finish")) {
      int version = order.path("version").asInt();
      var response =
          request(
              m,
              "POST",
              "/repairs/" + id + "/" + action,
              java.util.Map.of(
                  "expectedVersion",
                  version,
                  "resultDescription",
                  "已检查修复",
                  "resultImageIds",
                  java.util.List.of()));
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      order = json.readTree(response.body()).path("data");
    }
    var confirm =
        request(
            s,
            "POST",
            "/repairs/" + id + "/confirm",
            java.util.Map.of("expectedVersion", order.path("version").asInt()));
    assertThat(confirm.statusCode()).isEqualTo(200);
    assertThat(json.readTree(confirm.body()).path("data").path("status").asText())
        .isEqualTo("COMPLETED");
    assertThat(
            json.readTree(request(s, "GET", "/repairs/" + id + "/events", null).body())
                .path("data")
                .size())
        .isEqualTo(5);
  }

  @Test
  void hygieneRequiresUniqueRoundAndTracesCorrection() throws Exception {
    String manager = token("manager1");
    var rooms =
        json.readTree(request(manager, "GET", "/rooms", null).body()).path("data").path("items");
    String room = rooms.path(0).path("id").asText();
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("roomId", room);
    body.put(
        "inspectionDate", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());
    body.put("round", "TEST-" + System.nanoTime() % 1000000);
    for (String field : java.util.List.of("floorScore", "deskScore", "bedScore", "bathroomScore"))
      body.put(field, 23);
    body.put("imageIds", java.util.List.of());
    var created = request(manager, "POST", "/hygiene-inspections", body);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
    var h = json.readTree(created.body()).path("data");
    assertThat(h.path("totalScore").asInt()).isEqualTo(92);
    assertThat(request(manager, "POST", "/hygiene-inspections", body).statusCode()).isEqualTo(409);
    body.put("floorScore", 0);
    body.put("expectedVersion", 0);
    body.put("correctionReason", "测试修正");
    String id = h.path("id").asText();
    assertThat(request(manager, "PATCH", "/hygiene-inspections/" + id, body).statusCode())
        .isEqualTo(200);
    assertThat(
            json.readTree(
                    request(manager, "GET", "/hygiene-inspections/" + id + "/history", null).body())
                .path("data")
                .size())
        .isEqualTo(2);
  }

  @Test
  void concurrentManagersCannotOverbookLastRoomPlace() throws Exception {
    String m1 = token("manager1"),
        m2 = token("manager2"),
        suffix = Long.toString(System.nanoTime());
    String building =
        json.readTree(request(m2, "GET", "/buildings", null).body())
            .path("data")
            .path("items")
            .path(0)
            .path("id")
            .asText();
    var created =
        request(
            m1,
            "POST",
            "/rooms",
            java.util.Map.of(
                "buildingId", building, "roomNo", "T" + suffix, "floorNo", 1, "capacity", 1));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
    String room = json.readTree(created.body()).path("data").path("id").asText();
    var ids = new java.util.ArrayList<String>();
    for (int i = 0; i < 2; i++) {
      var s =
          request(
              m1,
              "POST",
              "/students",
              java.util.Map.of(
                  "studentNo",
                  "T" + suffix + i,
                  "name",
                  "并发测试" + i,
                  "college",
                  "测试学院",
                  "phone",
                  "13800000000"));
      assertThat(s.statusCode()).as(s.body()).isEqualTo(200);
      ids.add(json.readTree(s.body()).path("data").path("id").asText());
    }
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () ->
                  request(
                      m1,
                      "POST",
                      "/residences/check-in",
                      java.util.Map.of("studentId", ids.get(0), "roomId", room)));
      var second =
          pool.submit(
              () ->
                  request(
                      m2,
                      "POST",
                      "/residences/check-in",
                      java.util.Map.of("studentId", ids.get(1), "roomId", room)));
      assertThat(java.util.List.of(first.get().statusCode(), second.get().statusCode()))
          .containsExactlyInAnyOrder(200, 409);
    }
    assertThat(
            json.readTree(request(m1, "GET", "/rooms/" + room, null).body())
                .path("data")
                .path("occupiedCount")
                .asInt())
        .isEqualTo(1);
  }

  @Test
  void uploadsAreReencodedAndPrivateUntilAndAfterBinding() throws Exception {
    String student = token("student1"),
        other = token("student2"),
        boundary = "DormLinkTestBoundary";
    var png = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "png",
        png);
    var body = new java.io.ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"usage\"\r\n\r\nREPORT\r\n--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"test.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    body.write(png.toByteArray());
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/files"))
                .header("Authorization", "Bearer " + student)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    String id = json.readTree(response.body()).path("data").path("id").asText();
    assertThat(request(other, "GET", "/files/" + id + "/content", null).statusCode())
        .isEqualTo(404);
    var order =
        request(
            student,
            "POST",
            "/repairs",
            java.util.Map.of(
                "title",
                "图片测试",
                "description",
                "仅为附件权限测试",
                "category",
                "OTHER",
                "priority",
                "NORMAL",
                "contactName",
                "测试学生",
                "contactPhone",
                "13800000000",
                "reportImageIds",
                java.util.List.of(id)));
    assertThat(order.statusCode()).as(order.body()).isEqualTo(200);
    assertThat(request(other, "GET", "/files/" + id + "/content", null).statusCode())
        .isEqualTo(404);
    assertThat(request(student, "GET", "/files/" + id + "/content", null).statusCode())
        .isEqualTo(200);
    assertThat(request(student, "DELETE", "/files/" + id, null).statusCode()).isEqualTo(404);
  }

  @Test
  void nativeWebpMultipartUsesImageIoSpiAndIsReencodedWithoutClientConversion() throws Exception {
    byte[] webp =
        java.util.Base64.getDecoder()
            .decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
    try (var input =
        javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(webp))) {
      var readers = javax.imageio.ImageIO.getImageReaders(input);
      assertThat(readers.hasNext()).as("a real WebP ImageIO SPI is installed").isTrue();
      var reader = readers.next();
      try {
        assertThat(reader.getClass().getName()).contains("twelvemonkeys");
        reader.setInput(input);
        assertThat(reader.read(0).getWidth()).isEqualTo(1);
      } finally {
        reader.dispose();
      }
    }
    String t = token("student1"), boundary = "DirectWebpBoundary";
    var bytes = new java.io.ByteArrayOutputStream();
    bytes.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"usage\"\r\n\r\nREPORT\r\n--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"pixel.webp\"\r\n"
                + "Content-Type: image/webp\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    bytes.write(webp);
    bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/files"))
                .header("Authorization", "Bearer " + t)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var asset = json.readTree(response.body()).path("data");
    assertThat(asset.path("mime").asText()).isEqualTo("image/jpeg");
    assertThat(asset.path("originalName").asText()).isEqualTo("pixel.webp");
    assertThat(
            request(t, "GET", "/files/" + asset.path("id").asText() + "/content", null)
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void newlyCreatedInactiveStudentCannotAuthenticate() throws Exception {
    String username = "I" + System.nanoTime();
    var created =
        request(
            token("manager1"),
            "POST",
            "/students",
            java.util.Map.of(
                "studentNo",
                username,
                "name",
                "停用测试学生",
                "college",
                "虚构学院",
                "phone",
                "13800000000",
                "status",
                "INACTIVE"));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(
                            java.util.Map.of(
                                "username",
                                username,
                                "password",
                                "Demo@123456",
                                "clientType",
                                "MOBILE"))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void corruptedPngReturnsReadableUnsupportedMediaInsteadOfInternalError() throws Exception {
    String t = token("student1"), boundary = "BadPngBoundary";
    byte[] png =
        java.util.Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==");
    var bytes = new java.io.ByteArrayOutputStream();
    bytes.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"usage\"\r\n\r\nREPORT\r\n--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"corrupt.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n")
            .getBytes());
    bytes.write(png);
    bytes.write(("\r\n--" + boundary + "--\r\n").getBytes());
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/files"))
                .header("Authorization", "Bearer " + t)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(415);
    assertThat(response.body()).contains("FILE_DECODE_FAILED");
  }
}
