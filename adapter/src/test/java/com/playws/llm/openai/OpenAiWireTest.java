package com.playws.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Protocol mapping only — no HTTP. Contract: {@code docs/specs/m0-tool-call-round-trip.md}. */
class OpenAiWireTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Deterministic id synthesis: fixed ids, and a counter keeps each call unique. */
  private static OpenAiWire wire() {
    AtomicInteger seq = new AtomicInteger();
    return new OpenAiWire(() -> "call_fixed" + seq.incrementAndGet());
  }

  // ------------------------------------------------------------- request side

  @Test
  void renders_system_user_and_assistant_messages() {
    LlmRequest request =
        LlmRequest.builder()
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder().role("system").parts(Part.fromText("Be terse.")).build())
                    .build())
            .contents(
                List.of(
                    Content.builder().role("user").parts(Part.fromText("hi")).build(),
                    Content.builder()
                        .role("model")
                        .parts(Part.fromText("hello"))
                        .build()))
            .build();

    assertThat(json(wire().renderRequest(request, "deepseek-v4-flash")))
        .isEqualTo(
            json(
                """
                {
                  "model": "deepseek-v4-flash",
                  "messages": [
                    {"role": "system", "content": "Be terse."},
                    {"role": "user", "content": "hi"},
                    {"role": "assistant", "content": "hello"}
                  ]
                }
                """));
  }

  @Test
  void omits_system_message_when_no_system_instruction() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(Content.builder().role("user").parts(Part.fromText("hi")).build()))
            .build();

    JsonNode body = wire().renderRequest(request, "m");
    assertThat(body.path("messages")).hasSize(1);
    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
  }

  @Test
  void renders_single_tool_call_with_preserved_id() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("user")
                        .parts(Part.fromText("what time?"))
                        .build(),
                    Content.builder()
                        .role("model")
                        .parts(functionCallPart("adk-1", "get_time", Map.of("timezone", "UTC")))
                        .build()))
            .build();

    assertThat(json(wire().renderRequest(request, "m")).path("messages").get(1))
        .isEqualTo(
            json(
                """
                {
                  "role": "assistant",
                  "tool_calls": [
                    {
                      "id": "adk-1",
                      "type": "function",
                      "function": {"name": "get_time", "arguments": "{\\"timezone\\":\\"UTC\\"}"}
                    }
                  ]
                }
                """));
  }

  @Test
  void synthesizes_call_id_when_function_call_has_none() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("model")
                        .parts(functionCallPart(null, "get_time", Map.of()))
                        .build()))
            .build();

    JsonNode toolCall =
        wire().renderRequest(request, "m").path("messages").get(0).path("tool_calls").get(0);
    assertThat(toolCall.path("id").asText()).isEqualTo("call_fixed1");
  }

  @Test
  void synthesized_id_does_not_reuse_an_id_already_in_history() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("model")
                        .parts(functionCallPart("call_fixed1", "first", Map.of()))
                        .build(),
                    Content.builder()
                        .role("model")
                        .parts(functionCallPart(null, "second", Map.of()))
                        .build()))
            .build();

    JsonNode messages = wire().renderRequest(request, "m").path("messages");
    assertThat(messages.get(1).path("tool_calls").get(0).path("id").asText())
        .isEqualTo("call_fixed2");
  }

  @Test
  void folds_text_and_parallel_calls_into_one_assistant_message() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("model")
                        .parts(
                            Part.fromText("Checking "),
                            functionCallPart("call_1", "get_weather", Map.of("city", "Hanoi")),
                            functionCallPart("call_2", "get_weather", Map.of("city", "Tokyo")))
                        .build()))
            .build();

    JsonNode messages = wire().renderRequest(request, "m").path("messages");
    assertThat(messages).hasSize(1);
    assertThat(messages.get(0).path("content").asText()).isEqualTo("Checking ");
    assertThat(messages.get(0).path("tool_calls")).hasSize(2);
    assertThat(messages.get(0).path("tool_calls").get(0).path("function").path("arguments").asText())
        .isEqualTo("{\"city\":\"Hanoi\"}");
    assertThat(messages.get(0).path("tool_calls").get(1).path("function").path("arguments").asText())
        .isEqualTo("{\"city\":\"Tokyo\"}");
  }

  @Test
  void renders_tool_result_as_tool_message_matched_by_id() {
    LlmRequest request = toolRoundTrip("adk-1", "adk-1", "get_time");

    JsonNode messages = wire().renderRequest(request, "m").path("messages");
    assertThat(messages).hasSize(3);
    assertThat(messages.get(2))
        .isEqualTo(
            json(
                """
                {
                  "role": "tool",
                  "tool_call_id": "adk-1",
                  "content": "{\\"iso\\":\\"2026-01-01T00:00:00Z\\"}"
                }
                """));
  }

  @Test
  void matches_tool_result_by_name_when_it_has_no_id() {
    LlmRequest request = toolRoundTrip("adk-1", null, "get_time");

    JsonNode messages = wire().renderRequest(request, "m").path("messages");
    assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo("adk-1");
  }

  @Test
  void rejects_tool_result_matching_nothing() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("user")
                        .parts(
                            functionResponsePart(null, "get_time", Map.of("iso", "x")))
                        .build()))
            .build();

    assertThatThrownBy(() -> wire().renderRequest(request, "m"))
        .isInstanceOf(OpenAiWire.MappingException.class)
        .hasMessage("unmatched tool response");
  }

  @Test
  void rejects_tool_result_matching_parallel_same_name_calls() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("model")
                        .parts(
                            functionCallPart("call_1", "get_weather", Map.of("city", "Hanoi")),
                            functionCallPart("call_2", "get_weather", Map.of("city", "Tokyo")))
                        .build(),
                    Content.builder()
                        .role("user")
                        .parts(functionResponsePart(null, "get_weather", Map.of("temp", 30)))
                        .build()))
            .build();

    assertThatThrownBy(() -> wire().renderRequest(request, "m"))
        .isInstanceOf(OpenAiWire.MappingException.class)
        .hasMessage("unmatched tool response");
  }

  @Test
  void id_less_result_takes_the_most_recent_unmatched_call_not_an_older_one() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    // Turn 1: called, never answered.
                    Content.builder()
                        .role("model")
                        .parts(functionCallPart("call_old", "get_weather", Map.of("city", "Hanoi")))
                        .build(),
                    Content.builder().role("user").parts(Part.fromText("and Tokyo?")).build(),
                    // Turn 2: same tool, called again.
                    Content.builder()
                        .role("model")
                        .parts(functionCallPart("call_new", "get_weather", Map.of("city", "Tokyo")))
                        .build(),
                    Content.builder()
                        .role("user")
                        .parts(functionResponsePart(null, "get_weather", Map.of("temp", 18)))
                        .build()))
            .build();

    JsonNode messages = wire().renderRequest(request, "m").path("messages");

    // [model call_old] [user "and Tokyo?"] [model call_new] [tool result]
    assertThat(messages.get(3).path("role").asText()).isEqualTo("tool");
    assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("call_new");
  }

  @Test
  void blank_arguments_from_the_provider_mean_an_empty_object() {
    String body =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
            + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"now\","
            + "\"arguments\":\"\"}}]}}]}";

    LlmResponse response = wire().parseResponse(emptyRequest(), body);

    assertThat(response.errorMessage()).isEmpty();
    FunctionCall call = response.content().orElseThrow().parts().orElseThrow().get(0).functionCall().orElseThrow();
    assertThat(call.args()).isPresent();
    assertThat(call.args().get()).isEmpty();
  }

  @Test
  void emits_tool_messages_before_user_text() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("model")
                        .parts(functionCallPart("adk-1", "get_time", Map.of()))
                        .build(),
                    Content.builder()
                        .role("user")
                        .parts(
                            functionResponsePart("adk-1", "get_time", Map.of("iso", "x")),
                            Part.fromText("and tomorrow?"))
                        .build()))
            .build();

    JsonNode messages = wire().renderRequest(request, "m").path("messages");
    assertThat(messages).hasSize(3);
    assertThat(messages.get(1).path("role").asText()).isEqualTo("tool");
    assertThat(messages.get(2).path("role").asText()).isEqualTo("user");
    assertThat(messages.get(2).path("content").asText()).isEqualTo("and tomorrow?");
  }

  @Test
  void renders_tools_with_lowercased_json_schema_types() {
    Schema parameters =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                Map.of(
                    "timezone",
                    Schema.builder().type(Type.Known.STRING).description("IANA zone").build(),
                    "limit",
                    Schema.builder().type(Type.Known.INTEGER).build()))
            .required(List.of("timezone"))
            .build();
    LlmRequest request =
        LlmRequest.builder()
            .tools(Map.of("get_time", new StubTool("get_time", "Current time", parameters)))
            .build();

    JsonNode tools = wire().renderRequest(request, "m").path("tools");
    assertThat(tools)
        .isEqualTo(
            json(
                """
                [
                  {
                    "type": "function",
                    "function": {
                      "name": "get_time",
                      "description": "Current time",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "timezone": {"type": "string", "description": "IANA zone"},
                          "limit": {"type": "integer"}
                        },
                        "required": ["timezone"]
                      }
                    }
                  }
                ]
                """));
  }

  @Test
  void skips_tool_without_declaration() {
    LlmRequest request =
        LlmRequest.builder().tools(Map.of("nameless", new StubTool("nameless", "no decl", null))).build();

    JsonNode body = wire().renderRequest(request, "m");
    assertThat(body.has("tools")).isFalse();
  }

  // ------------------------------------------------------------ response side

  @Test
  void parses_text_only_response_as_complete_turn() {
    LlmResponse response = wire().parseResponse(emptyRequest(), fixture("text-only.json"));

    assertThat(response.errorMessage()).isEmpty();
    assertThat(response.turnComplete()).contains(true);
    assertThat(text(response)).isEqualTo("Hello there.");
  }

  @Test
  void parses_single_tool_call() {
    LlmResponse response = wire().parseResponse(emptyRequest(), fixture("single-tool-call.json"));

    assertThat(response.errorMessage()).isEmpty();
    assertThat(response.turnComplete()).contains(false);
    assertThat(response.content().orElseThrow().parts().orElseThrow()).hasSize(1);
    FunctionCall call = response.content().orElseThrow().parts().orElseThrow().get(0).functionCall().orElseThrow();
    assertThat(call.id()).contains("call_abc");
    assertThat(call.name()).contains("get_time");
    assertThat(call.args()).contains(Map.of("timezone", "UTC"));
  }

  @Test
  void parses_parallel_tool_calls_in_order() {
    LlmResponse response = wire().parseResponse(emptyRequest(), fixture("parallel-tool-calls.json"));

    List<Part> parts = response.content().orElseThrow().parts().orElseThrow();
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().orElseThrow().id()).contains("call_1");
    assertThat(parts.get(0).functionCall().orElseThrow().args()).contains(Map.of("city", "Hanoi"));
    assertThat(parts.get(1).functionCall().orElseThrow().id()).contains("call_2");
  }

  @Test
  void parses_text_before_tool_calls() {
    LlmResponse response =
        wire().parseResponse(emptyRequest(), fixture("mixed-text-and-call.json"));

    List<Part> parts = response.content().orElseThrow().parts().orElseThrow();
    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).text()).contains("Checking both cities.");
    assertThat(parts.get(1).functionCall()).isPresent();
    assertThat(parts.get(2).functionCall()).isPresent();
  }

  @Test
  void synthesizes_id_for_response_tool_call_without_one() {
    LlmResponse response = wire().parseResponse(emptyRequest(), fixture("missing-id.json"));

    FunctionCall call = onlyFunctionCall(response);
    assertThat(call.id()).contains("call_fixed1");
    assertThat(response.turnComplete()).contains(false);
  }

  @Test
  void reports_provider_error_envelope() {
    LlmResponse response = wire().parseResponse(emptyRequest(), fixture("provider-error.json"));

    assertThat(response.errorMessage())
        .contains("provider error: {\"message\":\"rate limited\",\"type\":\"rate_limit_error\"}");
    assertThat(response.turnComplete()).contains(false);
    assertThat(response.content()).isEmpty();
  }

  @Test
  void reports_empty_choices() {
    assertThat(wire().parseResponse(emptyRequest(), fixture("empty-choices.json")).errorMessage())
        .contains("empty choices");
  }

  @Test
  void reports_invalid_body() {
    assertThat(wire().parseResponse(emptyRequest(), "not json at all").errorMessage())
        .contains("invalid response body: not json at all");
  }

  @Test
  void reports_error_envelope_before_empty_choices() {
    String body = "{\"error\":{\"message\":\"boom\"},\"choices\":[]}";

    assertThat(wire().parseResponse(emptyRequest(), body).errorMessage())
        .contains("provider error: {\"message\":\"boom\"}");
  }

  @Test
  void malformed_arguments_alone_yield_error_only() {
    LlmResponse response = wire().parseResponse(emptyRequest(), fixture("bad-arguments.json"));

    assertThat(response.errorMessage()).contains("invalid tool arguments: timezone=UTC");
    assertThat(response.turnComplete()).contains(false);
    assertThat(response.content()).isEmpty();
  }

  @Test
  void arguments_with_trailing_garbage_are_rejected() {
    String body =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
            + "{\"id\":\"call_x\",\"type\":\"function\",\"function\":{\"name\":\"get_time\","
            + "\"arguments\":\"{} garbage\"}}]}}]}";

    LlmResponse response = wire().parseResponse(emptyRequest(), body);

    assertThat(response.errorMessage()).contains("invalid tool arguments: {} garbage");
    assertThat(response.turnComplete()).contains(false);
    assertThat(response.content()).isEmpty();
  }

  @Test
  void response_body_with_trailing_garbage_is_rejected() {
    LlmResponse response =
        wire().parseResponse(emptyRequest(), fixture("text-only.json") + " garbage");

    assertThat(response.errorMessage())
        .hasValueSatisfying(message -> assertThat(message).startsWith("invalid response body:"));
    assertThat(response.turnComplete()).contains(false);
  }

  @Test
  void non_array_tool_calls_is_an_error_not_a_completed_turn() {
    String body =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
            + "\"tool_calls\":{}}}]}";

    LlmResponse response = wire().parseResponse(emptyRequest(), body);

    assertThat(response.errorMessage())
        .hasValueSatisfying(message -> assertThat(message).startsWith("invalid response body:"));
    assertThat(response.turnComplete()).contains(false);
  }

  @Test
  void malformed_arguments_keep_the_good_parts() {
    String body =
        """
        {"choices":[{"message":{"role":"assistant","content":"done","tool_calls":[
          {"id":"call_ok","type":"function","function":{"name":"get_time","arguments":"{}"}},
          {"id":"call_bad","type":"function","function":{"name":"get_time","arguments":"nope"}}]}}]}
        """;

    LlmResponse response = wire().parseResponse(emptyRequest(), body);

    assertThat(response.errorMessage()).contains("invalid tool arguments: nope");
    List<Part> parts = response.content().orElseThrow().parts().orElseThrow();
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).text()).contains("done");
    assertThat(parts.get(1).functionCall().orElseThrow().id()).contains("call_ok");
  }

  @Test
  void tool_call_without_name_keeps_the_good_parts() {
    String body =
        """
        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
          {"id":"call_1","type":"function","function":{"name":"","arguments":"{}"}},
          {"id":"call_2","type":"function","function":{"name":"get_time","arguments":"{}"}}]}}]}
        """;

    LlmResponse response = wire().parseResponse(emptyRequest(), body);

    assertThat(response.errorMessage()).contains("tool call without name");
    assertThat(response.content().orElseThrow().parts().orElseThrow()).hasSize(1);
  }

  @Test
  void tool_call_without_name_alone_yields_error_only() {
    String body =
        "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
            + "\"function\":{\"name\":null,\"arguments\":\"{}\"}}]}}]}";

    LlmResponse response = wire().parseResponse(emptyRequest(), body);

    assertThat(response.errorMessage()).contains("tool call without name");
    assertThat(response.content()).isEmpty();
  }

  @Test
  void rejects_duplicate_id_within_the_response() {
    String body =
        """
        {"choices":[{"message":{"tool_calls":[
          {"id":"call_1","type":"function","function":{"name":"a","arguments":"{}"}},
          {"id":"call_1","type":"function","function":{"name":"b","arguments":"{}"}}]}}]}
        """;

    assertThat(wire().parseResponse(emptyRequest(), body).errorMessage())
        .contains("duplicate tool call id: call_1");
  }

  @Test
  void rejects_id_reused_from_request_history() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("model")
                        .parts(functionCallPart("call_1", "get_time", Map.of()))
                        .build()))
            .build();
    String body =
        "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
            + "\"function\":{\"name\":\"get_time\",\"arguments\":\"{}\"}}]}}]}";

    assertThat(wire().parseResponse(request, body).errorMessage())
        .contains("duplicate tool call id: call_1");
  }

  // ------------------------------------------------------------------ helpers

  private static JsonNode json(Object node) {
    try {
      return MAPPER.readTree(MAPPER.writeValueAsString(node));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode json(String raw) {
    try {
      return MAPPER.readTree(raw);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String fixture(String name) {
    try (var stream = OpenAiWireTest.class.getResourceAsStream("/fixtures/" + name)) {
      assertThat(stream).as(name).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String text(LlmResponse response) {
    return response
        .content()
        .orElseThrow()
        .parts()
        .orElseThrow()
        .get(0)
        .text()
        .orElseThrow();
  }

  private static FunctionCall onlyFunctionCall(LlmResponse response) {
    return response
        .content()
        .orElseThrow()
        .parts()
        .orElseThrow()
        .get(0)
        .functionCall()
        .orElseThrow();
  }

  private static LlmRequest emptyRequest() {
    return LlmRequest.builder().build();
  }

  private static LlmRequest toolRoundTrip(String callId, String responseId, String toolName) {
    return LlmRequest.builder()
        .contents(
            List.of(
                Content.builder()
                    .role("user")
                    .parts(Part.fromText("what time?"))
                    .build(),
                Content.builder()
                    .role("model")
                    .parts(functionCallPart(callId, toolName, Map.of("timezone", "UTC")))
                    .build(),
                Content.builder()
                    .role("user")
                    .parts(functionResponsePart(responseId, toolName, Map.of("iso", "2026-01-01T00:00:00Z")))
                    .build()))
        .build();
  }

  private static Part functionCallPart(String id, String name, Map<String, Object> args) {
    FunctionCall.Builder call = FunctionCall.builder().name(name).args(args);
    if (id != null) {
      call.id(id);
    }
    return Part.builder().functionCall(call.build()).build();
  }

  private static Part functionResponsePart(String id, String name, Map<String, Object> response) {
    FunctionResponse.Builder fn = FunctionResponse.builder().name(name).response(response);
    if (id != null) {
      fn.id(id);
    }
    return Part.builder().functionResponse(fn.build()).build();
  }

  /** Minimal {@link BaseTool} carrying only a declaration — the wire never invokes it. */
  private static final class StubTool extends BaseTool {
    private final Optional<FunctionDeclaration> declaration;

    StubTool(String name, String description, Schema parameters) {
      super(name, description);
      this.declaration =
          parameters == null
              ? Optional.empty()
              : Optional.of(
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
      return Single.just(Map.of());
    }
  }
}