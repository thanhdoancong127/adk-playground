package com.playws.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.playws.config.AdapterSettings;
import io.reactivex.rxjava3.core.Flowable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link BaseLlm} adapter for an OpenAI-compatible chat-completions endpoint (here: opencode-go).
 * ADK Java ships Gemini/Claude/Apigee models only, so this bridges any OpenAI-compatible
 * provider into an ADK {@code LlmAgent}.
 *
 * <p>Two responsibilities, deliberately split: {@link OpenAiWire} owns the protocol mapping, this
 * class owns transport and configuration. Provider-side problems come back as {@code
 * LlmResponse.errorMessage}; only transport failures surface as {@code Flowable.error}.
 *
 * <p>Env config (see {@code docs/TECHNICAL-NOTES.md}): {@code OPENCODE_BASE_URL} (default {@code
 * https://opencode.ai/zen/go/v1}), {@code OPENCODE_API_KEY}, {@code OPENCODE_SESSION} (auto UUID if
 * unset); the model id is the constructor arg.
 */
public class OpenCodeLlm extends BaseLlm {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

  private final AdapterSettings settings;
  private final HttpClient http;
  private final OpenAiWire wire;

  /** Env-driven constructor — the CLI path, unchanged in behavior. */
  public OpenCodeLlm(String model) {
    this(AdapterSettings.fromEnv(model));
  }

  /** Explicit settings; {@code http} may be null, in which case a client is built here. */
  public OpenCodeLlm(AdapterSettings settings) {
    super(settings.model());
    this.settings = settings;
    this.http =
        settings.http() != null
            ? settings.http()
            : HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    this.wire = new OpenAiWire();
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    return Flowable.fromCallable(() -> callModel(llmRequest));
  }

  private LlmResponse callModel(LlmRequest req) throws Exception {
    ObjectNode body;
    try {
      body = wire.renderRequest(req, model());
    } catch (OpenAiWire.MappingException e) {
      return OpenAiWire.error(e.getMessage());
    }

    HttpRequest httpReq =
        HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/chat/completions"))
            .timeout(settings.timeout())
            .header("Authorization", "Bearer " + settings.apiKey())
            .header("x-opencode-session", settings.session())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();

    HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      return OpenAiWire.error("HTTP " + resp.statusCode() + ": " + resp.body());
    }
    return wire.parseResponse(req, resp.body());
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException("Live connection is not supported by OpenCodeLlm");
  }
}
