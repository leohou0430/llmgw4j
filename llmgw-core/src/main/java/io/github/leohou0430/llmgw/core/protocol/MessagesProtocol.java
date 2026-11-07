package io.github.leohou0430.llmgw.core.protocol;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;

/**
 * Anthropic Messages API ({@code POST /v1/messages}). Pass-through parity: the
 * upstream must speak Anthropic's protocol; only {@code model}/{@code stream}/
 * {@code system}/{@code messages} text is extracted for governance.
 */
public final class MessagesProtocol {

    private MessagesProtocol() {
    }

    public static ParsedRequest parse(String rawJson) {
        String model = "default";
        boolean stream = false;
        StringBuilder prompt = new StringBuilder();
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
                } else if ("stream".equals(name)) {
                    stream = ProtocolCodec.boolOrSkip(p, value, stream);
                } else if ("system".equals(name)) {
                    collectBlocks(p, value, prompt);
                } else if ("messages".equals(name) && value == JsonToken.START_ARRAY) {
                    JsonToken t;
                    while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
                        if (t == JsonToken.START_OBJECT) {
                            collectMessage(p, prompt);
                        } else if (t == JsonToken.START_ARRAY) {
                            p.skipChildren();
                        }
                    }
                } else if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    p.skipChildren();
                }
            }
        }
        String text = prompt.toString();
        return new ParsedRequest(rawJson, ProtocolKind.MESSAGES, model, stream, text,
                ProtocolCodec.estimateTokens(text), 1,
                stream ? null : ProtocolCodec.fingerprint(rawJson));
    }

    /** {@code system} is a string or an array of {@code {type:"text",text}} blocks. */
    private static void collectBlocks(JsonParser p, JsonToken value, StringBuilder prompt) {
        if (value == JsonToken.VALUE_STRING) {
            prompt.append(p.getText());
            return;
        }
        if (value == JsonToken.START_ARRAY) {
            JsonToken t;
            while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
                if (t == JsonToken.START_OBJECT) {
                    appendTextField(p, prompt);
                } else if (t == JsonToken.START_ARRAY) {
                    p.skipChildren();
                }
            }
        } else if (value == JsonToken.START_OBJECT) {
            p.skipChildren();
        }
    }

    /** One message object; its {@code content} is a string or a block array. */
    private static void collectMessage(JsonParser p, StringBuilder prompt) {
        JsonToken f;
        while ((f = p.nextToken()) == JsonToken.PROPERTY_NAME) {
            String name = p.currentName();
            JsonToken v = p.nextToken();
            if ("content".equals(name)) {
                collectBlocks(p, v, prompt);
            } else if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                p.skipChildren();
            }
        }
    }

    /** Appends the {@code text} field of one content block, if textual. */
    private static void appendTextField(JsonParser p, StringBuilder prompt) {
        JsonToken f;
        while ((f = p.nextToken()) == JsonToken.PROPERTY_NAME) {
            String name = p.currentName();
            JsonToken v = p.nextToken();
            if ("text".equals(name) && v == JsonToken.VALUE_STRING) {
                prompt.append(p.getText());
            } else if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                p.skipChildren();
            }
        }
    }

    /** {@code usage.output_tokens} is authoritative; falls back to content text. */
    public static int completionTokens(JsonNode root) {
        if (root == null) {
            return 0;
        }
        JsonNode usage = root.get("usage");
        if (usage != null) {
            JsonNode tokens = usage.get("output_tokens");
            if (tokens != null && tokens.isNumber()) {
                return Math.max(0, tokens.asInt());
            }
        }
        return ProtocolCodec.estimateTokens(contentText(root));
    }

    private static String contentText(JsonNode root) {
        StringBuilder text = new StringBuilder();
        JsonNode content = root.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                JsonNode node = block.get("text");
                if (node != null && node.isTextual()) {
                    text.append(node.asText());
                }
            }
        }
        return text.toString();
    }

    /** Only a final assistant message with content may enter the semantic cache. */
    public static boolean cacheable(JsonNode root) {
        if (root == null || !root.isObject() || root.has("error")) {
            return false;
        }
        JsonNode content = root.get("content");
        JsonNode stopReason = root.get("stop_reason");
        return content != null && content.isArray() && !content.isEmpty()
                && stopReason != null && stopReason.isTextual() && !stopReason.asText().isBlank();
    }
}
