package com.workshop.adkplayground;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Loads configuration from a local {@code .env} file (via dotenv-java), falling back to real
 * environment variables. Keeps API keys out of the code and out of the repo (.env is gitignored).
 */
public final class Env {

  private static final Dotenv DOTENV =
      Dotenv.configure().ignoreIfMissing().ignoreIfMalformed().load();

  private Env() {}

  /** Resolve a key from the process environment first, then the .env file, else fallback. */
  public static String get(String key, String fallback) {
    String v = System.getenv(key);
    if (v == null || v.isBlank()) {
      v = DOTENV.get(key);
    }
    return (v == null || v.isBlank()) ? fallback : v;
  }

  public static String get(String key) {
    return get(key, "");
  }
}
