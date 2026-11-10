package io.github.leohou0430.llmgw.core.protocol;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;

/**
 * The five wire-protocol implementations. Each delegates to the parsing
 * internals it has always used (ProtocolCodec / the per-protocol classes /
 * ChunkParser); the value added here is the SEAM: behavior is reachable
 * through one interface instead of four closed switches.
 */
public final class DefaultProtocols {

    private DefaultProtocols() {
    }


    /**
     * W4.3 multipart upload kinds (transcriptions, files): the probe scans the
     * raw bytes for one form field; the body always forwards byte-for-byte.
     */
    public static class Multipart implements Protocol {
        private final ProtocolKind kind;
        private final String fieldName;
        protected Multipart(ProtocolKind kind, String fieldName) { this.kind = kind; this.fieldName = fieldName; }

        @Override public ProtocolKind kind() {
            return kind;
        }

        @Override public ParsedRequest parse(String rawJson) {
            // JSON entry points never exist for these kinds; the byte
            // variant is the real probe. Decode for safety.
            return parse(rawJson == null ? new byte[0] : rawJson.getBytes(StandardCharsets.UTF_8));
        }

        @Override public ParsedRequest parse(byte[] body) {
            String model = MultipartProbe.field(body, fieldName);
            return new ParsedRequest("", kind, model, false,
                    MultipartProbe.field(body, "prompt"),
                    MultipartProbe.estimateTokens(body), 1, null);
        }

        @Override public ChunkParser.SseChunk parseChunk(String rawLine) {
            return ChunkParser.SseChunk.passthrough(rawLine);
        }

        @Override public int completionTokens(JsonNode root) {
            return 0;
        }

        @Override public boolean cacheable(JsonNode root) {
            return false;
        }

        @Override public boolean appendDoneAfterInBandError() {
            return false;
        }

        /** File upload responses carry the file id → sticky routing. */
        @Override public String taskId(JsonNode root) {
            return kind == ProtocolKind.FILES ? VideosProtocol.taskId(root) : "";
        }
    }

    /** W4.3 batches: JSON create + async job polling, sticky-routed like videos. */
    public static final class Batches implements Protocol {

        @Override public ProtocolKind kind() {
            return ProtocolKind.BATCHES;
        }

        @Override public ParsedRequest parse(String rawJson) {
            String model = "default";
            StringBuilder text = new StringBuilder();
            try (JsonParser p = ProtocolCodec.parser(rawJson)) {
                if (p.nextToken() != JsonToken.START_OBJECT) {
                    throw new IllegalArgumentException("request body must be a JSON object");
                }
                JsonToken field;
                while ((field = p.nextToken()) == JsonToken.PROPERTY_NAME) {
                    String name = p.currentName();
                    JsonToken value = p.nextToken();
                    if ("model".equals(name)) {
                        model = ProtocolCodec.scalarOrSkip(p, value, model);
                    } else if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                        p.skipChildren();
                    }
                }
            }
            return new ParsedRequest(rawJson, ProtocolKind.BATCHES, model, false, "",
                    1, 1, null);
        }

        @Override public ChunkParser.SseChunk parseChunk(String rawLine) {
            return ChunkParser.SseChunk.passthrough(rawLine);
        }

        @Override public int completionTokens(JsonNode root) {
            return 0;
        }

        @Override public boolean cacheable(JsonNode root) {
            return false;
        }

        @Override public boolean appendDoneAfterInBandError() {
            return false;
        }

        @Override public String taskId(JsonNode root) {
            return VideosProtocol.taskId(root);
        }
    }

    /**
     * W4.3 factory for the JSON/binary pass-through kinds: minimal probe,
     * no streaming, no cache, no tokens, no terminator appendix.
     */
    public static class JsonPassthrough implements Protocol {
        private final ProtocolKind kind;
        private final String textField;
        protected JsonPassthrough(ProtocolKind kind, String textField) { this.kind = kind; this.textField = textField; }

        @Override public ProtocolKind kind() {
            return kind;
        }

        @Override public ParsedRequest parse(String rawJson) {
            return JsonProtocols.parse(kind, rawJson, textField);
        }

        @Override public ChunkParser.SseChunk parseChunk(String rawLine) {
            return ChunkParser.SseChunk.passthrough(rawLine);
        }

        @Override public int completionTokens(JsonNode root) {
            return 0;
        }

        @Override public boolean cacheable(JsonNode root) {
            return false;
        }

        @Override public boolean appendDoneAfterInBandError() {
            return false;
        }
    }

    public static final class Chat implements Protocol {
        @Override public ProtocolKind kind() {
            return ProtocolKind.CHAT;
        }

        @Override public ParsedRequest parse(String rawJson) {
            return ProtocolCodec.parse(rawJson);
        }

        @Override public ChunkParser.SseChunk parseChunk(String rawLine) {
            return ChunkParser.parse(rawLine);
        }

        @Override public int completionTokens(JsonNode root) {
            return ProtocolCodec.completionTokensFromResponse(root);
        }

        @Override public boolean cacheable(JsonNode root) {
            return ProtocolCodec.isCacheableResponse(root);
        }

        @Override public boolean appendDoneAfterInBandError() {
            return true;
        }
    }

    public static final class Responses implements Protocol {
        @Override public ProtocolKind kind() {
            return ProtocolKind.RESPONSES;
        }

        @Override public ParsedRequest parse(String rawJson) {
            return ResponsesProtocol.parse(rawJson);
        }

        @Override public ChunkParser.SseChunk parseChunk(String rawLine) {
            return ChunkParser.parse(ProtocolKind.RESPONSES, rawLine);
        }

        @Override public int completionTokens(JsonNode root) {
            return ResponsesProtocol.completionTokens(root);
        }

        @Override public boolean cacheable(JsonNode root) {
            return ResponsesProtocol.cacheable(root);
        }

        @Override public boolean appendDoneAfterInBandError() {
            return false;
        }
    }

    public static final class Messages implements Protocol {
        @Override public ProtocolKind kind() {
            return ProtocolKind.MESSAGES;
        }

        @Override public ParsedRequest parse(String rawJson) {
            return MessagesProtocol.parse(rawJson);
        }

        @Override public ChunkParser.SseChunk parseChunk(String rawLine) {
            return ChunkParser.parse(ProtocolKind.MESSAGES, rawLine);
        }

        @Override public int completionTokens(JsonNode root) {
            return MessagesProtocol.completionTokens(root);
        }

        @Override public boolean cacheable(JsonNode root) {
            return MessagesProtocol.cacheable(root);
        }

        @Override public boolean appendDoneAfterInBandError() {
            return false;
        }
    }

    public static final class Images implements Protocol {
        @Override public ProtocolKind kind() {
            return ProtocolKind.IMAGES;
        }

        @Override public ParsedRequest parse(String rawJson) {
            return ImagesProtocol.parse(rawJson);
        }

        @Override public ChunkParser.SseChunk parseChunk(String rawLine) {
            return ChunkParser.SseChunk.passthrough(rawLine);
        }

        @Override public int completionTokens(JsonNode root) {
            return 0;
        }

        @Override public boolean cacheable(JsonNode root) {
            // Media responses are never cached, whatever the payload says.
            return false;
        }

        @Override public boolean appendDoneAfterInBandError() {
            return false;
        }
    }

    public static final class Videos implements Protocol {
        @Override public ProtocolKind kind() {
            return ProtocolKind.VIDEOS;
        }

        @Override public ParsedRequest parse(String rawJson) {
            return VideosProtocol.parse(rawJson);
        }

        @Override public ChunkParser.SseChunk parseChunk(String rawLine) {
            return ChunkParser.SseChunk.passthrough(rawLine);
        }

        @Override public int completionTokens(JsonNode root) {
            return 0;
        }

        @Override public boolean cacheable(JsonNode root) {
            return false;
        }

        @Override public boolean appendDoneAfterInBandError() {
            return false;
        }

        @Override public String taskId(JsonNode root) {
            return VideosProtocol.taskId(root);
        }
    }

    public static final class Embeddings extends JsonPassthrough { public Embeddings() { super(ProtocolKind.EMBEDDINGS, "input"); } }
    public static final class Rerank extends JsonPassthrough { public Rerank() { super(ProtocolKind.RERANK, "query"); } }
    public static final class Speech extends JsonPassthrough { public Speech() { super(ProtocolKind.SPEECH, "input"); } }
    public static final class Transcriptions extends Multipart { public Transcriptions() { super(ProtocolKind.TRANSCRIPTIONS, "model"); } }
    public static final class Files extends Multipart { public Files() { super(ProtocolKind.FILES, "purpose"); } }
}
