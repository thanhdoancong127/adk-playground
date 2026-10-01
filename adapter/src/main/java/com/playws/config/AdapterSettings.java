package com.playws.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

/**
 * Explicit configuration for an LLM adapter. Exists so tests (and the future {@code web}
 * composition root) can point an adapter at an in-process HTTP server instead of the ambient
 * environment — the env-based constructors stay thin wrappers over this record.
 *
 * @param baseUrl endpoint base, e.g. {@code https://opencode.ai/zen/go/v1} (no trailing slash)
 * @param apiKey bearer token, may be empty for local servers
 * @param session value of the opencode-specific {@code x-opencode-session} header
 * @param model model id sent to the endpoint
 * @param timeout per-request timeout
 * @param http client to use, or {@code null} to let the adapter build one
 */
public record AdapterSettings(
    String baseUrl,
    String apiKey,
    String session,
    String model,
    Duration timeout,
    HttpClient http) {

  public static final String DEFAULT_BASE_URL = "https://opencode.ai/zen/go/v1";

  /** Settings from the process env / {@code .env} (see {@link Env}); no injected client. */
  public static AdapterSettings fromEnv(String model) {
    return new AdapterSettings(
        Env.get("OPENCODE_BASE_URL", DEFAULT_BASE_URL),
        Env.get("OPENCODE_API_KEY", ""),
        Env.get("OPENCODE_SESSION", UUID.randomUUID().toString()),
        model,
        Duration.ofSeconds(90),
        null);
  }

  /** Records print everywhere; the bearer token must not. */
  @Override
  public String toString() {
    return "AdapterSettings[baseUrl="
        + baseUrl
        + ", apiKey="
        + (apiKey == null || apiKey.isEmpty() ? "" : "***")
        + ", session="
        + session
        + ", model="
        + model
        + ", timeout="
        + timeout
        + "]";
  }
}
