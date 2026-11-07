package io.github.leohou0430.llmgw.core.protocol;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Jackson 3 based minimal protocol codec. Does NOT re-serialize provider payloads. */
public final class ProtocolCodec {

    /**
     * Shared failure reason for a stream that ends cleanly (EOF) without ever
     * carrying the protocol terminator. Both data planes must use the exact
     * same wording so the error contract is identical across engines.
     */
    public static final String TRUNCATED_MESSAGE =
            "upstream closed SSE stream without [DONE]";

    /** Protocol-aware truncation wording; identical across both data planes. */
    public static String truncatedMessage(ProtocolKind kind) {
        return kind == ProtocolKind.CHAT
                ? TRUNCATED_MESSAGE
                : "upstream closed SSE stream without " + kind.terminator();
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** Shared, thread-safe; streaming parsers are cheap per-request objects. */
    private static final JsonFactory JSON_FACTORY = MAPPER.tokenStreamFactory();
    private static final HexFormat HEX = HexFormat.of();
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    private ProtocolCodec() {
    }

    /**
     * Protocol dispatch. Each kind parses only the fields its governance needs
     * (model / stream / prompt text / units) and skips the rest without
     * materialising a tree; malformed JSON still throws so
     * {@code GatewayPipeline.enter} answers 400 exactly as before.
     */
    public static ParsedRequest parse(ProtocolKind kind, String rawJson) {
        return switch (kind) {
            case CHAT -> parse(rawJson);
            case RESPONSES -> ResponsesProtocol.parse(rawJson);
            case MESSAGES -> MessagesProtocol.parse(rawJson);
            case IMAGES -> ImagesProtocol.parse(rawJson);
            case VIDEOS -> VideosProtocol.parse(rawJson);
        };
    }

    /**
     * One streaming pass over the chat request body: a {@link JsonParser}
     * extracts exactly the fields the gateway needs and skips the rest without
     * materialising a single node. Unknown fields, non-textual (multimodal)
     * content and nested objects are skipped with {@code skipChildren()}.
     */
    public static ParsedRequest parse(String rawJson) {
        String model = "default";
        boolean stream = false;
        StringBuilder prompt = new StringBuilder();
        try (JsonParser p = JSON_FACTORY.createParser(rawJson == null ? "" : rawJson)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("request body must be a JSON object");
            }
            JsonToken field;
            while ((field = p.nextToken()) == JsonToken.PROPERTY_NAME) {
                String name = p.currentName();
                JsonToken value = p.nextToken();
                if ("model".equals(name)) {
                    if (value == JsonToken.VALUE_STRING) {
                        model = p.getText();
                    } else if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                        p.skipChildren();  // asText() on a container node was ""
                    } else if (value != null) {
                        model = p.getValueAsString("");
                    }
                } else if ("stream".equals(name)) {
                    if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                        p.skipChildren();
                    } else if (value != null) {
                        stream = p.getValueAsBoolean();
                    }
                } else if ("messages".equals(name) && value == JsonToken.START_ARRAY) {
                    collectPrompt(p, prompt);
                } else if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    p.skipChildren();
                }
            }
        }
        String text = prompt.toString();
        // The cache key must cover the WHOLE request, not just model + textual
        // prompt. Hashing only the prompt makes two requests with different
        // temperature / max_tokens / tools / multimodal content collide and
        // silently return each other's answer.
        //
        // Streaming responses are NEVER cached (both cache entry points in
        // GovernanceChain return early for stream=true), so hashing their body
        // is pure waste. Measured with bin/run-parse-microbench.sh: on a 4KB
        // request SHA-256 is 13.7us of the 20.2us parse (68%); on 16KB it is
        // 49us of 70us (70%). Defer it: cacheKey() computes the same full-body
        // fingerprint on demand if a cache lookup ever actually needs one.
        String fingerprint = stream ? null : fingerprint(rawJson);
        return new ParsedRequest(rawJson, ProtocolKind.CHAT, model, stream, text,
                estimateTokens(text), 1, fingerprint);
    }

    /** Shared per-request parser; streaming parsers are cheap objects. */
    static JsonParser parser(String rawJson) {
        return JSON_FACTORY.createParser(rawJson == null ? "" : rawJson);
    }

    /** String field read that tolerates containers by skipping them. */
    static String scalarOrSkip(JsonParser p, JsonToken value, String fallback) {
        if (value == JsonToken.VALUE_STRING) {
            return p.getText();
        }
        if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
            p.skipChildren();
        } else if (value != null) {
            String text = p.getValueAsString("");
            return text.isBlank() ? fallback : text;
        }
        return fallback;
    }

    /** Boolean field read that tolerates containers by skipping them. */
    static boolean boolOrSkip(JsonParser p, JsonToken value, boolean fallback) {
        if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
            p.skipChildren();
        } else if (value != null) {
            return p.getValueAsBoolean();
        }
        return fallback;
    }

    /** Parser is positioned on START_ARRAY of {@code messages}. */
    private static void collectPrompt(JsonParser p, StringBuilder prompt) {
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
            if (t == JsonToken.START_ARRAY) {
                p.skipChildren();
            } else if (t == JsonToken.START_OBJECT) {
                JsonToken f;
                while ((f = p.nextToken()) == JsonToken.PROPERTY_NAME) {
                    String name = p.currentName();
                    JsonToken v = p.nextToken();
                    if ("content".equals(name) && v == JsonToken.VALUE_STRING) {
                        prompt.append(p.getText());
                    } else if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                        p.skipChildren();
                    }
                }
            }
        }
    }

    /** Rough but deterministic estimator; replace with a real tokenizer in production. */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int len = text.length();
        long cjk = 0;
        // Plain loop: a Stream per request allocates + boxes for zero benefit
        // on the hot path.
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            }
        }
        return (int) Math.ceil(cjk + (len - cjk) / 4.0);
    }

    /** Stable fingerprint of the exact request bytes, used as the semantic-cache key. */
    public static String fingerprint(String rawJson) {
        // MessageDigest.getInstance() walks the JCA provider list under a lock;
        // reusing a per-thread digest removes that from the hot path. digest()
        // resets the instance, so no explicit reset() is needed.
        byte[] bytes = (rawJson == null ? "" : rawJson).getBytes(StandardCharsets.UTF_8);
        return HEX.formatHex(SHA256.get().digest(bytes));
    }

    /**
     * Rewrite ONLY the top-level "model" field for a route's target model.
     * <p>The old implementation used {@code String.replaceFirst} with a regex,
     * which (a) happily matched a {@code "model":} embedded inside a message
     * string, and (b) interpolated the replacement text, so a target model
     * containing {@code $} or {@code \} corrupted the JSON. Parsing into a tree
     * keeps every unknown/vendor field (including non-textual content arrays)
     * and changes exactly one node. This path is skipped entirely when no
     * target model is configured, which is the benchmark's default.
     */
    public static String withModel(String rawJson, String targetModel) {
        JsonNode root = MAPPER.readTree(rawJson);
        if (!root.isObject()) {
            return rawJson;
        }
        ((ObjectNode) root).put("model", targetModel);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * Single-parse helper for the non-streaming hot path: callers that need
     * BOTH the token count and the cacheability verdict parse the body ONCE
     * and pass the tree to the two node-based variants below. Vendor bodies
     * are typically 1-100KB; parsing them three times per request was pure
     * waste. Returns null when the body is not valid JSON (callers then bill
     * 0 tokens and skip the cache, same as the old String-based contract).
     */
    public static JsonNode tryParse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Real completion tokens from an OpenAI-compatible response. Falls back to
     * estimating the message content when the vendor omits {@code usage}.
     * Never estimates from the whole JSON envelope (ids, timestamps and object
     * names are not generated tokens).
     */
    public static int completionTokensFromResponse(JsonNode root) {
        if (root == null) {
            return 0;
        }
        JsonNode usage = root.get("usage");
        if (usage != null) {
            JsonNode tokens = usage.get("completion_tokens");
            if (tokens != null && tokens.isNumber()) {
                return Math.max(0, tokens.asInt());
            }
        }
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode content = choices.get(0).path("message").get("content");
            if (content != null && content.isTextual()) {
                return estimateTokens(content.asText());
            }
        }
        return 0;
    }

    public static int completionTokensFromResponse(String body) {
        return completionTokensFromResponse(tryParse(body));
    }

    /**
     * Kind-aware completion tokens. Text protocols read their authoritative
     * usage field (chat: {@code usage.completion_tokens}; responses/messages:
     * {@code usage.output_tokens}); media kinds never generate tokens.
     */
    public static int completionTokensFromResponse(ProtocolKind kind, JsonNode root) {
        return switch (kind) {
            case CHAT -> completionTokensFromResponse(root);
            case RESPONSES -> ResponsesProtocol.completionTokens(root);
            case MESSAGES -> MessagesProtocol.completionTokens(root);
            case IMAGES, VIDEOS -> 0;
        };
    }

    public static int completionTokensFromResponse(ProtocolKind kind, String body) {
        return completionTokensFromResponse(kind, tryParse(body));
    }

    /** Kind-aware cacheability; media responses are never cached. */
    public static boolean isCacheableResponse(ProtocolKind kind, JsonNode root) {
        return switch (kind) {
            case CHAT -> isCacheableResponse(root);
            case RESPONSES -> ResponsesProtocol.cacheable(root);
            case MESSAGES -> MessagesProtocol.cacheable(root);
            case IMAGES, VIDEOS -> false;
        };
    }

    public static boolean isCacheableResponse(ProtocolKind kind, String body) {
        return isCacheableResponse(kind, tryParse(body));
    }

    /** Only a well-formed completion response may enter the semantic cache. */
    public static boolean isCacheableResponse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        JsonNode choices = root.get("choices");
        return choices != null && choices.isArray() && !choices.isEmpty();
    }

    public static boolean isCacheableResponse(String body) {
        return isCacheableResponse(tryParse(body));
    }

    public static String dataLine(String json) {
        return "data: " + json + "\n\n";
    }

    public static String doneLine() {
        return "data: [DONE]\n\n";
    }

    public static String errorJson(String message) {
        return "{\"error\":{\"message\":\"" + escape(message) + "\",\"type\":\"gateway_error\"}}";
    }

    /**
     * In-band failure for a stream whose status/headers are already committed.
     * Never used before the first chunk -- there a real HTTP 502 is returned.
     */
    public static String errorEvent(String message) {
        return "event: error\ndata: " + errorJson(message) + "\n\n";
    }

    private static String escape(String text) {
        String value = text == null ? "" : text;
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    public static String toJson(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }
}
