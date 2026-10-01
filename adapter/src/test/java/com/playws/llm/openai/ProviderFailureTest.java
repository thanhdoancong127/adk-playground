package com.playws.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.SessionKey;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.playws.config.AdapterSettings;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the failure path through the real runner. ADK's {@code BaseLlmFlow} drops an
 * {@link com.google.adk.models.LlmResponse} that has no content and no {@code errorCode}, so a
 * provider error used to end the turn silently — the CLI printed nothing and no exception escaped.
 * The error must arrive as an event the caller can read.
 */
class ProviderFailureTest {

  @Test
  void provider_error_reaches_the_caller_instead_of_a_silent_empty_turn() {
    try (StubChatServer server = StubChatServer.start(500, "{\"error\":\"boom\"}")) {
      OpenCodeLlm llm =
          new OpenCodeLlm(
              new AdapterSettings(
                  server.baseUrl(), "", "sess-fail", "m", Duration.ofSeconds(5), null));
      LlmAgent agent =
          LlmAgent.builder().name("a").description("d").instruction("i").model(llm).build();

      InMemoryRunner runner = new InMemoryRunner(agent);
      SessionKey session =
          runner
              .sessionService()
              .createSession(runner.appName(), "u")
              .blockingGet()
              .sessionKey();

      List<Event> events =
          runner
              .runAsync(
                  session, Content.builder().role("user").parts(Part.fromText("hi")).build())
              .toList()
              .blockingGet();

      assertThat(events).hasSize(1);
      assertThat(events.get(0).errorCode())
          .hasValueSatisfying(code -> assertThat(code.toString()).isEqualTo("OTHER"));
      assertThat(events.get(0).errorMessage())
          .hasValueSatisfying(message -> assertThat(message).contains("HTTP 500").contains("boom"));
      assertThat(events.get(0).turnComplete()).contains(false);
    }
  }
}