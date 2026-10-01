package com.playws.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.SessionKey;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.playws.config.AdapterSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The M0 acceptance test: a full ADK turn over the real adapter — {@code InMemoryRunner ->
 * OpenCodeLlm -> HTTP -> real FunctionTool -> second HTTP request -> final answer}. No scripted
 * LLM and no network: the endpoint is {@link StubChatServer}, so a regression in id handling or
 * tool-role mapping fails here rather than in production.
 */
class ToolCallRoundTripTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String CALL_TIME =
      "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
          + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"getTime\","
          + "\"arguments\":\"{\\\"timezone\\\":\\\"UTC\\\"}\"}}]}}]}";
  private static final String FINAL_ANSWER =
      "{\"choices\":[{\"message\":{\"role\":\"assistant\","
          + "\"content\":\"It is 2026-01-01T00:00:00Z in UTC.\"}}]}";

  /**
   * The tool under test: a real ADK {@link FunctionTool} over a plain method. {@code @Schema} names
   * the parameter, which is both the JSON key the model must send and what ADK binds to.
   */
  public static final class TimeTools {
    public Map<String, Object> getTime(
        @Annotations.Schema(name = "timezone", description = "IANA zone, e.g. UTC") String timezone) {
      return Map.of("timezone", timezone, "iso", "2026-01-01T00:00:00Z");
    }
  }

  @Test
  void tool_call_is_executed_and_the_result_is_sent_back_for_the_final_answer() throws Exception {
    try (StubChatServer server = StubChatServer.start(CALL_TIME, FINAL_ANSWER)) {
      OpenCodeLlm llm =
          new OpenCodeLlm(
              new AdapterSettings(
                  server.baseUrl(), "", "sess-round-trip", "test-model",
                  Duration.ofSeconds(10), null));
      LlmAgent agent =
          LlmAgent.builder()
              .name("time-agent")
              .description("Answers time questions with the getTime tool.")
              .instruction("Always call getTime for time questions.")
              .model(llm)
              .tools(List.of(FunctionTool.create(new TimeTools(), "getTime")))
              .build();

      InMemoryRunner runner = new InMemoryRunner(agent);
      SessionKey session =
          runner.sessionService().createSession(runner.appName(), "user-1").blockingGet().sessionKey();

      List<Event> events =
          runner
              .runAsync(
                  session,
                  Content.builder().role("user").parts(Part.fromText("what time?")).build())
              .toList()
              .blockingGet();

      assertThat(lastText(events)).isEqualTo("It is 2026-01-01T00:00:00Z in UTC.");
      assertThat(server.requestCount()).isEqualTo(2);

      JsonNode firstRequest = MAPPER.readTree(server.request(0).body());
      assertThat(firstRequest.path("tools").get(0).path("function").path("name").asText())
          .isEqualTo("getTime");

      JsonNode secondRequest = MAPPER.readTree(server.request(1).body());
      List<JsonNode> messages = messages(secondRequest);
      JsonNode toolMessage = messages.get(messages.size() - 1);
      assertThat(toolMessage.path("role").asText()).isEqualTo("tool");
      assertThat(toolMessage.path("tool_call_id").asText()).isEqualTo("call_1");
      assertThat(toolMessage.path("content").asText()).contains("2026-01-01T00:00:00Z");
    }
  }

  private static List<JsonNode> messages(JsonNode request) {
    return MAPPER.convertValue(
        request.path("messages"),
        new com.fasterxml.jackson.core.type.TypeReference<List<JsonNode>>() {});
  }

  private static String lastText(List<Event> events) {
    Event last = events.get(events.size() - 1);
    return last.content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
  }
}