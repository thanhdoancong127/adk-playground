package com.workshop.adkplayground;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;

/** A minimal ADK agent backed by the opencode-go endpoint via {@link OpenCodeLlm}. */
public class DemoAgent {

  public static final BaseAgent ROOT_AGENT = initAgent();

  private static BaseAgent initAgent() {
    String model = Env.get("OPENCODE_MODEL", "deepseek-v4-flash");
    return LlmAgent.builder()
        .name("opencode-agent")
        .description("A minimal Google ADK agent running on the opencode-go endpoint")
        .instruction(
            "You are a concise, friendly assistant. Answer in one or two short sentences.")
        .model(new OpenCodeLlm(model))
        .build();
  }
}
