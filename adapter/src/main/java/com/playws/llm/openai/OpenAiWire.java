package com.playws.llm.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Pure ADK &lt;-&gt; OpenAI chat-completions protocol mapping: no HTTP, no environment. {@link
 * OpenCodeLlm} owns transport; this class owns the wire format, so the mapping is testable in
 * isolation (see {@code docs/specs/m0-tool-call-round-trip.md}).
 *
 * <p>Tool-call ids are the subtle part. A call id must be unique within the whole request
 * history, because a provider matches a later tool result by id, and ADK replays the full
 * history on every turn. Ids that arrive are propagated; missing ones are synthesized through
 * the injected {@link Supplier} so tests stay deterministic.
 */
public final class OpenAiWire {

  private static final Logger LOG = Logger.getLogger(OpenAiWire.class.getName());

  /** {@code errorCode} for any failed turn: HTTP, provider-side error, or unparseable body. */
  static final FinishReason PROVIDER_ERROR = new FinishReason(FinishReason.Known.OTHER);

  /** The only {@code type} values we lower-case; everything else is data, not a type. */
  private static final Set<String> SCHEMA_TYPES =
      Set.of("OBJECT", "STRING", "NUMBER", "INTEGER", "BOOLEAN", "ARRAY", "NULL");

  private final ObjectMapper mapper;
  /** Same mapper, but trailing garbage after the JSON value is an error, not noise. */
  private final ObjectReader strict;

  private final Supplier<String> callIdFactory;

  public OpenAiWire() {
    this(new ObjectMapper(), () -> "call_" + UUID.randomUUID());
  }

  public OpenAiWire(Supplier<String> callIdFactory) {
    this(new ObjectMapper(), callIdFactory);
  }

  OpenAiWire(ObjectMapper mapper, Supplier<String> callIdFactory) {
    this.mapper = mapper;
    this.strict = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.callIdFactory = callIdFactory;
  }

  /** Request mapping failed in a way that ADK must surface as an {@code errorMessage}. */
  public static final class MappingException extends RuntimeException {
    public MappingException(String message) {
      super(message);
    }
  }

  // ---------------------------------------------------------------- request

  /** Render an {@link LlmRequest} as an OpenAI chat-completions request body. */
  public ObjectNode renderRequest(LlmRequest request, String model) {
    ObjectNode body = mapper.createObjectNode();
    body.put("model", model);
    ArrayNode messages = body.putArray("messages");

    request
        .getFirstSystemInstruction()
        .ifPresent(
            instruction -> {
              ObjectNode m = messages.addObject();
              m.put("role", "system");
              m.put("content", instruction);
            });

    Set<String> usedIds = historyIds(request);
    List<Call> outstanding = new ArrayList<>();
    int turn = 0;
    for (Content content : request.contents()) {
      boolean isModel = "model".equals(content.role().orElse("user"));
      StringBuilder text = new StringBuilder();
      List<FunctionCall> calls = new ArrayList<>();
      List<FunctionResponse> responses = new ArrayList<>();
      List<Part> unmapped = new ArrayList<>();
      content
          .parts()
          .ifPresent(
              parts ->
                  parts.forEach(
                      part -> {
                        if (part.text().isPresent()) {
                          part.text().ifPresent(text::append);
                        } else if (part.functionCall().isPresent()) {
                          calls.add(part.functionCall().get());
                        } else if (part.functionResponse().isPresent()) {
                          responses.add(part.functionResponse().get());
                        } else {
                          unmapped.add(part);
                        }
                      }));

      if (!unmapped.isEmpty()) {
        LOG.warning("dropping " + unmapped.size() + " part(s) that map to no OpenAI message");
      }
      if (isModel && !responses.isEmpty()) {
        LOG.warning("dropping functionResponse part(s) in a model content");
      }
      if (!isModel && !calls.isEmpty()) {
        LOG.warning("dropping functionCall part(s) in a non-model content");
      }

      if (isModel) {
        turn++;
        ObjectNode message = null;
        if (text.length() > 0) {
          message = messages.addObject();
          message.put("role", "assistant");
          message.put("content", text.toString());
        }
        if (!calls.isEmpty()) {
          if (message == null) {
            message = messages.addObject();
          }
          message.put("role", "assistant");
          ArrayNode toolCalls = message.putArray("tool_calls");
          for (FunctionCall call : calls) {
            String name = call.name().orElse("");
            String id = call.id().filter(s -> !s.isBlank()).orElseGet(() -> freshId(usedIds));
            ObjectNode toolCall = toolCalls.addObject();
            toolCall.put("id", id);
            toolCall.put("type", "function");
            ObjectNode fn = toolCall.putObject("function");
            fn.put("name", name);
            fn.put("arguments", writeJson(call.args().orElse(Map.of())));
            outstanding.add(new Call(id, name, turn));
          }
        }
      } else {
        for (FunctionResponse response : responses) {
          ObjectNode message = messages.addObject();
          message.put("role", "tool");
          message.put("tool_call_id", matchCallId(response, outstanding));
          message.put("content", writeJson(response.response().orElse(Map.of())));
        }
        if (text.length() > 0) {
          ObjectNode message = messages.addObject();
          message.put("role", content.role().orElse("user"));
          message.put("content", text.toString());
        }
      }
    }

    renderTools(request, body);
    return body;
  }

  private void renderTools(LlmRequest request, ObjectNode body) {
    Map<String, BaseTool> tools = request.tools();
    if (tools == null || tools.isEmpty()) {
      return;
    }
    ArrayNode array = mapper.createArrayNode();
    for (BaseTool tool : tools.values()) {
      Optional<FunctionDeclaration> declaration = tool.declaration();
      if (declaration.isEmpty()) {
        LOG.fine(() -> "skipping tool without declaration: " + tool.name());
        continue;
      }
      FunctionDeclaration fn = declaration.get();
      ObjectNode entry = array.addObject();
      entry.put("type", "function");
      ObjectNode function = entry.putObject("function");
      fn.name().ifPresent(name -> function.put("name", name));
      fn.description().ifPresent(description -> function.put("description", description));
      ObjectNode parameters = jsonSchemaParameters(fn);
      if (parameters != null) {
        function.set("parameters", parameters);
      }
    }
    if (array.size() > 0) {
      body.set("tools", array);
    }
  }

  private ObjectNode jsonSchemaParameters(FunctionDeclaration fn) {
    if (fn.parametersJsonSchema().isPresent()) {
      try {
        return (ObjectNode) mapper.valueToTree(fn.parametersJsonSchema().get());
      } catch (RuntimeException e) {
        throw new MappingException("cannot convert tool schema: " + e.getMessage());
      }
    }
    Optional<Schema> schema = fn.parameters();
    if (schema.isEmpty()) {
      return null;
    }
    // Schema.toJson() already emits JSON-Schema keys but with upper-case types ("OBJECT"),
    // which OpenAI rejects; lower-case the types and pass everything else through.
    try {
      return (ObjectNode) lowerCaseTypes(mapper.readTree(schema.get().toJson()));
    } catch (Exception e) {
      throw new MappingException("cannot convert tool schema: " + e.getMessage());
    }
  }

  /**
   * Lower-cases JSON-Schema {@code type} values recursively. Keys of a {@code properties} object
   * are parameter names and are never rewritten, so a parameter literally named "type" is safe.
   */
  private JsonNode lowerCaseTypes(JsonNode node) {
    if (node.isObject()) {
      ObjectNode out = mapper.createObjectNode();
      node.fields()
          .forEachRemaining(
              entry -> {
                String key = entry.getKey();
                if ("type".equals(key)
                    && entry.getValue().isTextual()
                    && SCHEMA_TYPES.contains(entry.getValue().asText().toUpperCase(Locale.ROOT))) {
                  // Only a real schema type is rewritten: an "enum"/"default"/"example" value that
                  // happens to be the string "OBJECT" must survive untouched.
                  out.put(key, entry.getValue().asText().toLowerCase(Locale.ROOT));
                } else {
                  out.set(key, lowerCaseTypes(entry.getValue()));
                }
              });
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = mapper.createArrayNode();
      node.forEach(child -> out.add(lowerCaseTypes(child)));
      return out;
    }
    return node;
  }

  // --------------------------------------------------------------- response

  /**
   * Parse an OpenAI response body into an {@link LlmResponse}. Provider-side problems never
   * throw: they surface as {@code errorMessage}, per the M0 error table.
   */
  public LlmResponse parseResponse(LlmRequest request, String rawBody) {
    JsonNode root;
    try {
      root = strict.readTree(rawBody);
    } catch (Exception e) {
      return error("invalid response body: " + rawBody);
    }
    if (root == null || !root.isObject()) {
      return error("invalid response body: " + rawBody);
    }

    JsonNode providerError = root.get("error");
    if (providerError != null && !providerError.isNull()) {
      return error("provider error: " + providerError);
    }

    JsonNode choices = root.get("choices");
    if (choices == null || !choices.isArray() || choices.isEmpty()) {
      return error("empty choices");
    }
    JsonNode message = choices.get(0).get("message");
    if (message == null || !message.isObject()) {
      return error("invalid response body: " + rawBody);
    }

    List<Part> parts = new ArrayList<>();
    String firstError = null;
    JsonNode content = message.get("content");
    if (content != null && content.isTextual() && !content.asText().isEmpty()) {
      parts.add(Part.fromText(content.asText()));
    }

    JsonNode toolCalls = message.get("tool_calls");
    if (toolCalls != null && !toolCalls.isNull() && !toolCalls.isArray()) {
      // A malformed shape must not read as a completed, empty turn.
      return error("invalid response body: " + rawBody);
    }
    if (toolCalls != null && toolCalls.isArray()) {
      Set<String> usedIds = historyIds(request);
      for (JsonNode toolCall : toolCalls) {
        JsonNode fn = toolCall.get("function");
        String name = textOrNull(fn == null ? null : fn.get("name"));
        if (name == null || name.isBlank()) {
          firstError = first(firstError, "tool call without name");
          continue;
        }
        String rawArgs = textOrNull(fn.get("arguments"));
        Map<String, Object> args;
        try {
          // OpenAI sends "" for a tool with no parameters; that is an empty argument object.
          JsonNode parsed =
              rawArgs == null || rawArgs.isBlank()
                  ? mapper.createObjectNode()
                  : strict.readTree(rawArgs);
          if (parsed == null || !parsed.isObject()) {
            firstError = first(firstError, "invalid tool arguments: " + rawArgs);
            continue;
          }
          args = mapper.convertValue(parsed, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
          firstError = first(firstError, "invalid tool arguments: " + rawArgs);
          continue;
        }

        String id = textOrNull(toolCall.get("id"));
        if (id == null || id.isBlank()) {
          // A response id is new: ADK will match the later functionResponse against it.
          id = freshId(usedIds);
        } else if (!usedIds.add(id)) {
          firstError = first(firstError, "duplicate tool call id: " + id);
          continue;
        }
        parts.add(
            Part.builder()
                .functionCall(FunctionCall.builder().id(id).name(name).args(args).build())
                .build());
      }
    }

    boolean hasToolCall = parts.stream().anyMatch(part -> part.functionCall().isPresent());
    LlmResponse.Builder builder =
        LlmResponse.builder().turnComplete(!hasToolCall && firstError == null);
    if (!parts.isEmpty()) {
      builder.content(Content.builder().role("model").parts(parts).build());
    }
    if (firstError != null) {
      builder.errorMessage(firstError).errorCode(PROVIDER_ERROR);
    }
    return builder.build();
  }

  // ---------------------------------------------------------------- helpers

  /**
   * A failed turn, as ADK needs it: {@code errorCode} is mandatory, not decoration — {@code
   * BaseLlmFlow} drops a response that has no content and no {@code errorCode}, so an error without
   * it never reaches the caller at all (silent empty turn).
   */
  static LlmResponse error(String message) {
    return LlmResponse.builder()
        .errorMessage(message)
        .errorCode(PROVIDER_ERROR)
        .turnComplete(false)
        .build();
  }

  private static String first(String current, String candidate) {
    return current == null ? candidate : current;
  }

  private static String textOrNull(JsonNode node) {
    return node == null || node.isNull() ? null : node.asText();
  }

  private String writeJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new MappingException("cannot serialize tool payload: " + e.getMessage());
    }
  }

  private String freshId(Set<String> usedIds) {
    for (int attempt = 0; attempt < 5; attempt++) {
      String id = callIdFactory.get();
      if (usedIds.add(id)) {
        return id;
      }
      LOG.warning(() -> "synthesized tool-call id already used, retrying: " + id);
    }
    throw new MappingException("cannot synthesize a unique tool call id");
  }

  /** Every id already present in the replayed history, from calls and results alike. */
  private static Set<String> historyIds(LlmRequest request) {
    Set<String> ids = new HashSet<>();
    for (Content content : request.contents()) {
      content
          .parts()
          .ifPresent(
              parts ->
                  parts.forEach(
                      part -> {
                        part.functionCall().flatMap(FunctionCall::id).ifPresent(ids::add);
                        part.functionResponse().flatMap(FunctionResponse::id).ifPresent(ids::add);
                      }));
    }
    return ids;
  }

  /**
   * Resolve a tool result to the call it answers. An explicit id must match an outstanding call;
   * otherwise match the single outstanding call with that name — parallel same-name calls are
   * ambiguous, and guessing would silently mis-attribute a result.
   */
  private static String matchCallId(FunctionResponse response, List<Call> outstanding) {
    Optional<String> id = response.id().filter(s -> !s.isBlank());
    if (id.isPresent()) {
      for (Call call : outstanding) {
        if (!call.matched && call.id.equals(id.get())) {
          call.matched = true;
          return call.id;
        }
      }
      throw new MappingException("unmatched tool response");
    }

    // No id: fall back to the name. The most recent unmatched call of that name wins; only
    // several parallel calls of the same name in one turn are ambiguous. A stale call left over
    // from an earlier turn must not block a later result.
    String name = response.name().orElse("");
    int newest = -1;
    for (Call call : outstanding) {
      if (!call.matched && call.name.equals(name)) {
        newest = Math.max(newest, call.turn);
      }
    }
    if (newest < 0) {
      throw new MappingException("unmatched tool response");
    }
    Call candidate = null;
    for (Call call : outstanding) {
      if (!call.matched && call.name.equals(name) && call.turn == newest) {
        if (candidate != null) {
          throw new MappingException("unmatched tool response");
        }
        candidate = call;
      }
    }
    if (candidate == null) {
      throw new MappingException("unmatched tool response");
    }
    candidate.matched = true;
    return candidate.id;
  }

  private static final class Call {
    final String id;
    final String name;
    /** 1-based index of the model turn this call came from; used to disambiguate by recency. */
    final int turn;
    boolean matched;

    Call(String id, String name, int turn) {
      this.id = id;
      this.name = name;
      this.turn = turn;
      this.matched = false;
    }
  }
}
