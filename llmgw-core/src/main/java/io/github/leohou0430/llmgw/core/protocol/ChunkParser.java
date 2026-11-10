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
        return parse(ProtocolKind.CHAT, rawLine);
    }

    /**
     * Kind-aware SSE line parsing. All three streaming protocols send
     * {@code data:} lines whose JSON payload identifies the event; they differ
     * only in the terminator frame and where deltas/usage live:
     * <ul>
     *   <li>chat: {@code data: [DONE]}, deltas in {@code choices[].delta.content},
     *       usage in {@code usage.completion_tokens}</li>
     *   <li>responses: {@code type=response.completed}, deltas in {@code delta},
     *       usage in {@code response.usage.output_tokens}</li>
     *   <li>messages: {@code type=message_stop}, deltas in
     *       {@code delta.text}, usage in {@code usage.output_tokens}</li>
     * </ul>
     * Images/videos never stream. The raw line is never rewritten.
     */
    public static SseChunk parse(ProtocolKind kind, String rawLine) {
        if (kind == ProtocolKind.CHAT) {
            return chat(rawLine);
        }
        String payload = dataPayload(rawLine);
        if (payload == null) {
            return SseChunk.passthrough(rawLine == null ? "" : rawLine);
        }
        if ("[DONE]".equals(payload)) {
            // Defensive: a [DONE] on a responses/messages stream is still a
            // terminator, whatever the upstream chose to send.
            return new SseChunk(rawLine, "", true, 0, false);
        }
        String type = "";
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
                switch (field) {
                    case "type" -> {
                        if (v == JsonToken.VALUE_STRING) {
                            type = p.getText();
                        }
                    }
                    case "delta" -> {
                        if (v == JsonToken.VALUE_STRING) {
                            // responses: output_text.delta carries text directly.
                            delta = p.getText();
                        } else if (v == JsonToken.START_OBJECT) {
                            // messages: content_block_delta nests delta.text.
                            delta = nestedString(p, "text");
                        } else if (v == JsonToken.START_ARRAY) {
                            p.skipChildren();
                        }
                    }
                    case "usage" -> {
                        if (v == JsonToken.START_OBJECT) {
                            int[] found = readOutputTokens(p);
                            if (found[1] == 1) {
                                tokens = found[0];
                                hasUsage = true;
                            }
                        } else if (v == JsonToken.START_ARRAY) {
                            p.skipChildren();
                        }
                    }
                    case "response" -> {
                        if (v == JsonToken.START_OBJECT) {
                            // responses.completed: usage lives under response.
                            int[] found = walkResponseUsage(p);
                            if (found[1] == 1) {
                                tokens = found[0];
                                hasUsage = true;
                            }
                        } else if (v == JsonToken.START_ARRAY) {
                            p.skipChildren();
                        }
                    }
                    default -> {
                        if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                            p.skipChildren();
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return SseChunk.passthrough(rawLine);
        }
        boolean done = "response.completed".equals(type) || "message_stop".equals(type);
        return new SseChunk(rawLine, delta, done, tokens, hasUsage);
    }

    /**
     * Walks the {@code response} object of a responses.completed frame for
     * {@code usage.output_tokens}, skipping everything else. Parser enters on
     * START_OBJECT and leaves on its END_OBJECT.
     */
    private static int[] walkResponseUsage(JsonParser p) {
        int[] found = {0, 0};
        JsonToken t;
        while ((t = p.nextToken()) == JsonToken.PROPERTY_NAME) {
            String field = p.currentName();
            JsonToken v = p.nextToken();
            if ("usage".equals(field) && v == JsonToken.START_OBJECT) {
                found = readOutputTokens(p);
            } else if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                p.skipChildren();
            }
        }
        return found;
    }

    /** Reads {@code output_tokens} from a usage object; {value, foundFlag}. */
    private static int[] readOutputTokens(JsonParser p) {
        int[] found = {0, 0};
        JsonToken t;
        while ((t = p.nextToken()) == JsonToken.PROPERTY_NAME) {
            String field = p.currentName();
            JsonToken v = p.nextToken();
            if ("output_tokens".equals(field)
                    && (v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT)) {
                found[0] = p.getValueAsInt();
                found[1] = 1;
            } else if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                p.skipChildren();
            }
        }
        return found;
    }

    /** Reads one string field from a nested object, skipping the rest. */
    private static String nestedString(JsonParser p, String wanted) {
        String found = "";
        JsonToken t;
        while ((t = p.nextToken()) == JsonToken.PROPERTY_NAME) {
            String field = p.currentName();
            JsonToken v = p.nextToken();
            if (wanted.equals(field) && v == JsonToken.VALUE_STRING) {
                found = p.getText();
            } else if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                p.skipChildren();
            }
        }
        return found;
    }

    /** Preserve String.trim's ASCII boundary rules while copying the payload only once. */
    private static String dataPayload(String raw) {
        if (raw == null) return null;
        int start = 0, end = raw.length();
        while (start < end && raw.charAt(start) <= ' ') start++;
        while (end > start && raw.charAt(end - 1) <= ' ') end--;
        if (end - start < 5 || !raw.startsWith("data:", start)) return null;
        start += 5;
        while (start < end && raw.charAt(start) <= ' ') start++;
        return raw.substring(start, end);
    }

    private static SseChunk chat(String rawLine) {
        String payload = dataPayload(rawLine);
        if (payload == null) {
            return SseChunk.passthrough(rawLine == null ? "" : rawLine);
        }
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
