package cn.dormlink;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared provider transport; request identity and quota admission use short SQL-only transactions.
 */
@Service
class AiGateway {
  private final JsonMapper json;
  private final AiRequestLedger ledger;
  private final String base, key, model;
  private final int dailyLimit;
  private final Semaphore slots = new Semaphore(2);
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  AiGateway(
      JsonMapper json,
      AiRequestLedger ledger,
      @Value("${app.ai.base-url}") String base,
      @Value("${app.ai.api-key}") String key,
      @Value("${app.ai.model}") String model,
      @Value("${app.ai.daily-limit}") int dailyLimit) {
    this.json = json;
    this.ledger = ledger;
    this.base = base;
    this.key = key;
    this.model = model;
    this.dailyLimit = dailyLimit;
  }

  void requireConfigured() {
    if (key.isBlank()) throw new ApiException(503, "AI_NOT_CONFIGURED", "AI尚未配置，可继续人工填写或办理");
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> generate(
      AiCommand command,
      String system,
      List<Object> content,
      Function<Map<String, Object>, Map<String, Object>> validate) {
    requireConfigured();
    var request = ledger.identify(command);
    var cached = ledger.cached(request);
    if (cached != null) return cached;
    if (!slots.tryAcquire()) throw new ApiException(429, "AI_BUSY", "AI正在处理其他请求，请稍后重试");
    try {
      var admission = ledger.admit(request, model, dailyLimit);
      if (admission.cached() != null) return admission.cached();
      long started = System.currentTimeMillis();
      Map<String, Object> result;
      Integer input, output;
      try {
        String payload =
            json.writeValueAsString(
                Map.of(
                    "model",
                    model,
                    "messages",
                    List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", content)),
                    "max_tokens",
                    1400,
                    "temperature",
                    .2,
                    "response_format",
                    Map.of("type", "json_object"),
                    "thinking",
                    Map.of("type", "disabled")));
        var response =
            http.send(
                HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > 100000)
          throw new IllegalArgumentException("Invalid supplier response");
        var root = json.readTree(response.body());
        Map<String, Object> raw =
            json.readValue(
                root.path("choices").path(0).path("message").path("content").asText(), Map.class);
        result = new LinkedHashMap<>(validate.apply(raw));
        result.put("suggestionId", admission.invocationId());
        result.put("requiresHumanReview", true);
        input =
            root.path("usage").has("prompt_tokens")
                ? root.path("usage").path("prompt_tokens").asInt()
                : null;
        output =
            root.path("usage").has("completion_tokens")
                ? root.path("usage").path("completion_tokens").asInt()
                : null;
      } catch (IOException | InterruptedException uncertain) {
        if (uncertain instanceof InterruptedException) Thread.currentThread().interrupt();
        ledger.unknown(admission, System.currentTimeMillis() - started);
        throw new ApiException(409, "AI_RESULT_UNKNOWN", "AI调用结果尚未确认，本次不会自动重试；如需重新生成请明确发起新请求");
      } catch (Exception failed) {
        ledger.failure(admission, System.currentTimeMillis() - started);
        throw new ApiException(503, "AI_UNAVAILABLE", "AI响应失败或暂时不可用，可继续人工填写与办理");
      }
      try {
        ledger.success(admission, result, System.currentTimeMillis() - started, input, output);
      } catch (RuntimeException uncertain) {
        throw new ApiException(409, "AI_RESULT_UNKNOWN", "AI结果保存状态尚未确认，请使用原请求标识查询，避免重复调用");
      }
      return result;
    } finally {
      slots.release();
    }
  }

  static List<String> strings(Object raw, int max) {
    if (!(raw instanceof List<?> list) || list.size() > max) throw ApiException.bad("AI结构无效");
    var result = new ArrayList<String>();
    for (Object item : list) {
      if (!(item instanceof String text) || text.length() > 300) throw ApiException.bad("AI结构无效");
      result.add(text);
    }
    return List.copyOf(result);
  }
}
