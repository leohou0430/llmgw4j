package io.github.leohou0430.llmgw.core.protocol;

import tools.jackson.core.JsonParser;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JsonToken;

/** Parses SSE data lines for usage accounting without ever rewriting the raw line. */
public final class ChunkParser {

    /**
     * @param hasUsage true when the upstream sent an authoritative
     *                 {@code usage.completion_tokens} field. Such a value must
     *                 REPLACE the per-delta estimate, never be added to it --
     *                 otherwise a stream with 8 deltas and a usage chunk is
     *                 billed twice.
     */
    public record SseChunk(String raw, String delta, boolean done,
                           int completionTokens, boolean hasUsage) {
        public static SseChunk passthrough(String raw) {
            return new SseChunk(raw, "", false, 0, false);
        }
    }

    /** Shared, thread-safe; streaming parsers are created per line from it. */
    private static final JsonFactory JSON_FACTORY = ProtocolCodec.mapper().tokenStreamFactory();

    private ChunkParser() {
    }

    /**
     * Streaming extraction (Jackson {@link JsonParser}, no {@code JsonNode}
     * tree). This path sees ~10^5 lines/s on the wire profile (one SSE stream
     * carries 10-20 lines); building a full tree per line was the largest
     * remaining JSON allocation source on the streaming hot path.
     *
     * <p>Contract identical to the old tree version, with one deliberate
     * tightening: {@code usage.completion_tokens} is only honored as a JSON
     * number (the spec type), not as a coerced string.
     */
    public static SseChunk parse(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return SseChunk.passthrough(rawLine == null ? "" : rawLine);
        }
        String line = rawLine.trim();
        if (!line.startsWith("data:")) {
            return SseChunk.passthrough(rawLine);
        }
        String payload = line.substring(5).trim();
        if ("[DONE]".equals(payload)) {
            return new SseChunk(rawLine, "", true, 0, false);
        }
        String delta = "";
        int tokens = 0;
        boolean hasUsage = false;
        try (JsonParser p = JSON_FACTORY.createParser(payload)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return SseChunk.passthrough(rawLine);
            }
            JsonToken t;
            while ((t = p.nextToken()) == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken v = p.nextToken();
                if ("choices".equals(field) && v == JsonToken.START_ARRAY) {
                    JsonToken first = p.nextToken();
                    if (first == JsonToken.START_OBJECT) {
                        delta = readDeltaContent(p);
                    }
                    // Consume any remaining elements of the choices array.
                    JsonToken e;
                    while ((e = p.nextToken()) != JsonToken.END_ARRAY) {
                        if (e == null) {
                            return SseChunk.passthrough(rawLine);
                        }
                        if (e == JsonToken.START_OBJECT || e == JsonToken.START_ARRAY) {
                            p.skipChildren();
                        }
                    }
                } else if ("usage".equals(field) && v == JsonToken.START_OBJECT) {
                    JsonToken t2;
                    while ((t2 = p.nextToken()) == JsonToken.PROPERTY_NAME) {
                        String f2 = p.currentName();
                        JsonToken v2 = p.nextToken();
                        if ("completion_tokens".equals(f2)
                                && (v2 == JsonToken.VALUE_NUMBER_INT
                                    || v2 == JsonToken.VALUE_NUMBER_FLOAT)) {
                            tokens = p.getValueAsInt();
                            hasUsage = true;
                        } else if (v2 == JsonToken.START_OBJECT || v2 == JsonToken.START_ARRAY) {
                            p.skipChildren();
                        }
                    }
                } else if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                    p.skipChildren();
                }
            }
        } catch (RuntimeException ignored) {
            // Vendor-specific chunk: keep the raw line untouched, never break passthrough.
            return SseChunk.passthrough(rawLine);
        }
        return new SseChunk(rawLine, delta, false, tokens, hasUsage);
    }

    /**
     * Parser is positioned on START_OBJECT of choices[0]; walks it and returns
     * {@code delta.content} (empty when absent). Leaves the parser ON the
     * choice object's END_OBJECT.
     */
    private static String readDeltaContent(JsonParser p) {
        String delta = "";
        JsonToken t;
        while ((t = p.nextToken()) == JsonToken.PROPERTY_NAME) {
            String field = p.currentName();
            JsonToken v = p.nextToken();
            if ("delta".equals(field) && v == JsonToken.START_OBJECT) {
                JsonToken t2;
                while ((t2 = p.nextToken()) == JsonToken.PROPERTY_NAME) {
                    String f2 = p.currentName();
                    JsonToken v2 = p.nextToken();
                    if ("content".equals(f2) && v2 == JsonToken.VALUE_STRING) {
                        delta = p.getText();
                    } else if (v2 == JsonToken.START_OBJECT || v2 == JsonToken.START_ARRAY) {
                        p.skipChildren();
                    }
                }
            } else if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                p.skipChildren();
            }
        }
        return delta;
    }
}
