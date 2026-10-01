package com.workshop.adkplayground;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

/** Non-interactive runner: sends one prompt and prints the agent's final response. */
public class DemoRunner {

  public static void main(String[] args) {
    String prompt =
        args.length > 0 ? String.join(" ", args) : "What is Google ADK? Answer in one sentence.";

    InMemoryRunner runner = new InMemoryRunner(DemoAgent.ROOT_AGENT);
    Session session =
        runner.sessionService().createSession(runner.appName(), "user-1").blockingGet();

    Content userMsg = Content.fromParts(Part.fromText(prompt));
    Flowable<Event> events =
        runner.runAsync(session.userId(), session.id(), userMsg, RunConfig.builder().build());

    System.out.println("You   > " + prompt);
    events.blockingForEach(
        event -> {
          if (event.finalResponse()) {
            System.out.println("Agent > " + event.stringifyContent());
          }
        });
  }
}
