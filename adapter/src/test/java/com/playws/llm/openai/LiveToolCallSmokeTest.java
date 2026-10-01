package com.playws.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.SessionKey;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.playws.config.AdapterSettings;
import com.playws.config.Env;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Manual smoke against the real configured provider — never part of {@code mvn verify}
 * (excluded by {@code surefire.excludedGroups=live}).
 *
 * <pre>
 * mvn -pl adapter test -Dgroups=live -Dsurefire.excludedGroups=
 * </pre>
 *
 * <p>Reads {@code .env} through {@code Env}, so the live provider, key and model are the ones the
 * CLI uses. Recorded result: see {@code docs/TECHNICAL-NOTES.md}.
 */
@Tag("live")
class LiveToolCallSmokeTest {

  /** Returns a fixed sentinel, so the assertion can demand the exact string back. */
  public static final class LiveTools {
    static final String SENTINEL = "2026-01-01T00:00:00Z";

    public Map<String, Object> getTime(
        @Annotations.Schema(name = "timezone", description = "IANA zone, e.g. UTC") String timezone) {
      return Map.of("timezone", timezone, "iso", SENTINEL);
    }
  }

  @Test
  void live_provider_calls_the_tool_and_summarizes_the_result() {
    OpenCodeLlm llm =
        new OpenCodeLlm(
            AdapterSettings.fromEnv(Env.get("OPENCODE_MODEL", "deepseek-v4-flash")));
    LlmAgent agent =
        LlmAgent.builder()
            .name("time-agent")
            .description("Answers time questions with the getTime tool.")
            .instruction(
                "You must call getTime with timezone \"UTC\" before answering a time question, "
                    + "then state the ISO timestamp you were given.")
            .model(llm)
            .tools(List.of(FunctionTool.create(new LiveTools(), "getTime")))
            .build();

    InMemoryRunner runner = new InMemoryRunner(agent);
    SessionKey session =
        runner
            .sessionService()
            .createSession(runner.appName(), "live-smoke")
            .blockingGet()
            .sessionKey();

    List<Event> events =
        runner
            .runAsync(
                session,
                Content.builder()
                    .role("user")
                    .parts(Part.fromText("What time is it? Use the tool."))
                    .build())
            .toList()
            .blockingGet();

    List<String> texts =
        events.stream()
            .flatMap(
                event ->
                    event.content().stream()
                        .flatMap(content -> content.parts().stream())
                        .flatMap(parts -> parts.stream())
                        .flatMap(part -> part.text().stream()))
            .toList();
    List<String> errors =
        events.stream()
            .flatMap(event -> event.errorMessage().stream())
            .toList();
    System.out.println("[live] model=" + llm.model() + " texts=" + texts + " errors=" + errors);

    // Three things must hold, or the tool was never really called: no provider error, a
    // functionResponse event carrying the tool's output, and the answer repeating that output.
    assertThat(errors).as("no provider error").isEmpty();

    List<String> toolResults =
        events.stream()
            .flatMap(
                event ->
                    event.content().stream()
                        .flatMap(content -> content.parts().stream())
                        .flatMap(parts -> parts.stream())
                        .flatMap(
                            part ->
                                part.functionResponse().stream()
                                    .map(response -> response.name().orElse("") + " " + response.response().orElseThrow().toString())))
            .toList();
    System.out.println("[live] toolResults=" + toolResults);
    assertThat(toolResults).as("the tool actually ran").hasSize(1).anyMatch(r -> r.contains(LiveTools.SENTINEL));

    assertThat(texts).as("live model answered").isNotEmpty();
    assertThat(String.join(" ", texts)).contains(LiveTools.SENTINEL);
  }
}