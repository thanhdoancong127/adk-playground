package com.playws.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Transport + error-table contract of {@link OpenCodeLlm} against an in-process server. */
class OpenCodeLlmTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The same fixture files the wire tests use, so both layers assert the same bytes. */
  private static String fixture(String name) {
    try (java.io.InputStream in =
        OpenCodeLlmTest.class.getResourceAsStream("/fixtures/" + name)) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new java.io.UncheckedIOException("cannot read fixture " + name, new java.io.IOException(e));
    }
  }

  private static OpenCodeLlm llm(String baseUrl, String apiKey, String session) {
    return new OpenCodeLlm(
        new com.playws.config.AdapterSettings(
            baseUrl, apiKey, session, "test-model", Duration.ofSeconds(10), null));
  }

  private static LlmResponse call(OpenCodeLlm llm, LlmRequest request) {
    return llm.generateContent(request, false).blockingFirst();
  }

  @Test
  void sends_headers_and_parses_text() throws Exception {
    try (StubChatServer server = StubChatServer.start(fixture("text-only.json"))) {
      LlmResponse response = call(llm(server.baseUrl(), "k-123", "sess-1"), request());

      StubChatServer.Captured captured = server.request(0);
      assertThat(captured.authorization()).isEqualTo("Bearer k-123");
      assertThat(captured.session()).isEqualTo("sess-1");
      assertThat(captured.contentType()).isEqualTo("application/json");
      assertThat(MAPPER.readTree(captured.body()).path("model").asText()).isEqualTo("test-model");
      assertThat(response.errorMessage()).isEmpty();
      assertThat(response.turnComplete()).contains(true);
    }
  }

  @Test
  void sends_tool_declarations_and_tool_result_as_tool_role() throws Exception {
    try (StubChatServer server = StubChatServer.start(fixture("text-only.json"))) {
      call(llm(server.baseUrl(), "", "s"), toolRoundTripRequest());

      JsonNode body = MAPPER.readTree(server.request(0).body());
      assertThat(body.path("tools").get(0).path("function").path("name").asText())
          .isEqualTo("get_time");
      assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("assistant");
      JsonNode toolMessage = body.path("messages").get(2);
      assertThat(toolMessage.path("role").asText()).isEqualTo("tool");
      assertThat(toolMessage.path("tool_call_id").asText()).isEqualTo("call_1");
      assertThat(toolMessage.path("content").asText()).isEqualTo("{\"iso\":\"2026-01-01T00:00:00Z\"}");
    }
  }

  @Test
  void reports_http_error() {
    try (StubChatServer server = StubChatServer.start(503, "upstream down")) {
      LlmResponse response = call(llm(server.baseUrl(), "", "s"), request());

      assertThat(response.errorMessage()).contains("HTTP 503: upstream down");
      // errorCode is not cosmetic: without it ADK drops the event and the turn ends silently.
      assertThat(response.errorCode()).isPresent();
      assertThat(response.turnComplete()).contains(false);
    }
  }

  @Test
  void reports_provider_error_envelope() {
    try (StubChatServer server =
        StubChatServer.start(fixture("provider-error.json"))) {
      LlmResponse response = call(llm(server.baseUrl(), "", "s"), request());
      assertThat(response.errorMessage())
          .hasValueSatisfying(m -> assertThat(m).startsWith("provider error:").contains("rate limited"));
      assertThat(response.errorCode()).isPresent();
    }
  }

  @Test
  void reports_invalid_body() {
    try (StubChatServer server = StubChatServer.start("<html>nope</html>")) {
      assertThat(call(llm(server.baseUrl(), "", "s"), request()).errorMessage())
          .contains("invalid response body: <html>nope</html>");
    }
  }

  @Test
  void reports_empty_choices() {
    try (StubChatServer server = StubChatServer.start(fixture("empty-choices.json"))) {
      assertThat(call(llm(server.baseUrl(), "", "s"), request()).errorMessage())
          .contains("empty choices");
    }
  }

  @Test
  void reports_unmatched_tool_response_before_calling_out() {
    try (StubChatServer server = StubChatServer.start(fixture("text-only.json"))) {
      LlmRequest request =
          LlmRequest.builder()
              .contents(
                  List.of(
                      Content.builder()
                          .role("user")
                          .parts(
                              Part.builder()
                                  .functionResponse(
                                      FunctionResponse.builder()
                                          .name("get_time")
                                          .response(Map.of("iso", "x"))
                                          .build())
                                  .build())
                          .build()))
              .build();

      LlmResponse response = call(llm(server.baseUrl(), "", "s"), request);

      assertThat(response.errorMessage()).contains("unmatched tool response");
      assertThat(response.turnComplete()).contains(false);
      assertThat(server.requestCount()).isZero();
    }
  }

  @Test
  void maps_tool_call_response() throws Exception {
    try (StubChatServer server = StubChatServer.start(fixture("single-tool-call.json"))) {
      LlmResponse response = call(llm(server.baseUrl(), "", "s"), request());

      assertThat(server.requestCount()).isEqualTo(1);
      assertThat(MAPPER.readTree(server.request(0).body()).path("tools")).isEmpty();

      Part part = response.content().orElseThrow().parts().orElseThrow().get(0);
      FunctionCall call = part.functionCall().orElseThrow();
      assertThat(call.id()).contains("call_abc");
      assertThat(call.name()).contains("get_time");
      assertThat(call.args().orElseThrow()).containsEntry("timezone", "UTC");
      assertThat(response.turnComplete()).contains(false);
      assertThat(response.errorMessage()).isEmpty();
    }
  }

  @Test
  void transport_failure_is_an_error_not_a_response() {
    // Nothing listening on port 1: a transport failure must surface as Flowable.error, because
    // pretending it were a provider response would let a retry loop swallow it.
    OpenCodeLlm llm = llm("http://127.0.0.1:1", "", "s");

    assertThatThrownBy(() -> call(llm, request()))
        .hasRootCauseInstanceOf(java.io.IOException.class);
  }

  private static LlmRequest request() {
    return LlmRequest.builder()
        .config(
            GenerateContentConfig.builder()
                .systemInstruction(
                    Content.builder().role("system").parts(Part.fromText("Be terse.")).build())
                .build())
        .contents(
            List.of(Content.builder().role("user").parts(Part.fromText("hi")).build()))
        .build();
  }

  private static LlmRequest toolRoundTripRequest() {
    Schema parameters =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(Map.of("timezone", Schema.builder().type(Type.Known.STRING).build()))
            .required(List.of("timezone"))
            .build();
    return LlmRequest.builder()
        .tools(Map.of("get_time", new StubTool("get_time", "Current time", parameters)))
        .contents(
            List.of(
                Content.builder().role("user").parts(Part.fromText("what time?")).build(),
                Content.builder()
                    .role("model")
                    .parts(
                        Part.builder()
                            .functionCall(
                                FunctionCall.builder()
                                    .id("call_1")
                                    .name("get_time")
                                    .args(Map.of("timezone", "UTC"))
                                    .build())
                            .build())
                    .build(),
                Content.builder()
                    .role("user")
                    .parts(
                        Part.builder()
                            .functionResponse(
                                FunctionResponse.builder()
                                    .id("call_1")
                                    .name("get_time")
                                    .response(Map.of("iso", "2026-01-01T00:00:00Z"))
                                    .build())
                            .build())
                    .build()))
        .build();
  }

  private static final class StubTool extends BaseTool {
    private final Optional<FunctionDeclaration> declaration;

    StubTool(String name, String description, Schema parameters) {
      super(name, description);
      this.declaration =
          Optional.of(
              FunctionDeclaration.builder()
                  .name(name)
                  .description(description)
                  .parameters(parameters)
                  .build());
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return declaration;
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext ctx) {
      return Single.just(Map.of("iso", "2026-01-01T00:00:00Z"));
    }
  }
}