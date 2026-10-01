package com.playws.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playws.config.Env;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Minimal {@link BaseLlm} adapter that talks to an OpenAI-compatible chat-completions endpoint
 * (here: opencode-go). ADK Java ships Gemini/Claude/Apigee models only, so this bridges any
 * OpenAI-compatible provider into an ADK {@code LlmAgent}.
 *
 * <p>Config via env: {@code OPENCODE_BASE_URL} (default https://opencode.ai/zen/go/v1),
 * {@code OPENCODE_API_KEY}, {@code OPENCODE_SESSION} (auto UUID if unset). The model id is the
 * constructor arg.
 */
public class OpenCodeLlm extends BaseLlm {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String DEFAULT_BASE = "https://opencode.ai/zen/go/v1";

  private final String baseUrl;
  private final String apiKey;
  private final String session;

  public OpenCodeLlm(String model) {
    super(model);
    this.baseUrl = Env.get("OPENCODE_BASE_URL", DEFAULT_BASE);
    this.apiKey = Env.get("OPENCODE_API_KEY", "");
    this.session = Env.get("OPENCODE_SESSION", UUID.randomUUID().toString());
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    return Flowable.fromCallable(() -> callModel(llmRequest));
  }

  private LlmResponse callModel(LlmRequest req) throws Exception {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", model());
    ArrayNode messages = body.putArray("messages");

    // system instruction -> messages[0]
    req.getFirstSystemInstruction()
        .ifPresent(
            si -> {
              ObjectNode m = messages.addObject();
              m.put("role", "system");
              m.put("content", si);
            });

    // ADK contents -> OpenAI messages
    for (Content c : req.contents()) {
      StringBuilder sb = new StringBuilder();
      c.parts().ifPresent(parts -> parts.forEach(p -> p.text().ifPresent(sb::append)));
      if (sb.length() == 0) {
        continue;
      }
      String role = c.role().orElse("user");
      ObjectNode m = messages.addObject();
      m.put("role", role.equals("model") ? "assistant" : role);
      m.put("content", sb.toString());
    }

    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    HttpRequest httpReq =
        HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
            .timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer " + apiKey)
            .header("x-opencode-session", session)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();

    HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      return LlmResponse.builder()
          .errorMessage("HTTP " + resp.statusCode() + ": " + resp.body())
          .build();
    }

    JsonNode root = MAPPER.readTree(resp.body());
    String text = root.path("choices").path(0).path("message").path("content").asText("");
    Content content = Content.builder().role("model").parts(Part.fromText(text)).build();
    return LlmResponse.builder().content(content).turnComplete(true).build();
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException("Live connection is not supported by OpenCodeLlm");
  }
}
