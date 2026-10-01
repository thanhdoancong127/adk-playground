package com.playws.llm.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stand-in for an OpenAI-compatible {@code /chat/completions} endpoint. Serves the
 * scripted bodies in order (the last one repeats), records every request, and never touches the
 * network — that is the whole point of the M0 seam.
 */
final class StubChatServer implements AutoCloseable {

  record Captured(String body, String contentType, String authorization, String session) {}

  private final HttpServer server;
  private final String baseUrl;
  private final Queue<Captured> requests = new ConcurrentLinkedQueue<>();

  private StubChatServer(HttpServer server) {
    this.server = server;
    this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  static StubChatServer start(String... bodies) {
    return start(200, bodies);
  }

  static StubChatServer start(int status, String... bodies) {
    List<String> scripted = List.of(bodies);
    AtomicInteger cursor = new AtomicInteger();
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      StubChatServer stub = new StubChatServer(server);
      server.createContext(
          "/v1/chat/completions",
          exchange -> {
            stub.handle(exchange, status, scripted, cursor);
          });
      server.start();
      return stub;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void handle(
      HttpExchange exchange, int status, List<String> scripted, AtomicInteger cursor)
      throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add(
        new Captured(
            body,
            exchange.getRequestHeaders().getFirst("Content-Type"),
            exchange.getRequestHeaders().getFirst("Authorization"),
            exchange.getRequestHeaders().getFirst("x-opencode-session")));
    byte[] out =
        scripted.get(Math.min(cursor.getAndIncrement(), scripted.size() - 1))
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, out.length);
    exchange.getResponseBody().write(out);
    exchange.close();
  }

  String baseUrl() {
    return baseUrl;
  }

  int requestCount() {
    return requests.size();
  }

  Captured request(int index) {
    return requests.stream().skip(index).findFirst().orElseThrow();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}