package io.github.leohou0430.llmgw.core.protocol;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;

/**
 * OpenAI Responses API ({@code POST /v1/responses}). Same pass-through contract
 * as chat: only {@code model}/{@code stream}/{@code input} are extracted for
 * routing, estimation and cache keying; everything else forwards verbatim.
 */
public final class ResponsesProtocol {

    private ResponsesProtocol() {
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
                } else if ("instructions".equals(name) && value == JsonToken.VALUE_STRING) {
                    prompt.append(p.getText());
                } else if ("input".equals(name)) {
                    collectInput(p, value, prompt, 0);
                } else if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    p.skipChildren();
                }
            }
        }
        String text = prompt.toString();
        return new ParsedRequest(rawJson, ProtocolKind.RESPONSES, model, stream, text,
                ProtocolCodec.estimateTokens(text), 1,
                stream ? null : ProtocolCodec.fingerprint(rawJson));
    }

    /** {@code input} is either a plain string or an array of input items. */
    private static void collectInput(JsonParser p, JsonToken value,
                                     StringBuilder prompt, int depth) {
        if (value == JsonToken.VALUE_STRING) {
            prompt.append(p.getText());
            return;
        }
        if (value != JsonToken.START_ARRAY || depth > 2) {
            if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                p.skipChildren();
            }
            return;
        }
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
            if (t == JsonToken.START_OBJECT) {
                collectInputObject(p, prompt, depth);
            } else if (t == JsonToken.START_ARRAY) {
                p.skipChildren();
            }
        }
    }

    /** One input item: may carry text directly or nested under {@code content}. */
    private static void collectInputObject(JsonParser p, StringBuilder prompt, int depth) {
        JsonToken f;
        while ((f = p.nextToken()) == JsonToken.PROPERTY_NAME) {
            String name = p.currentName();
            JsonToken v = p.nextToken();
            if ("text".equals(name) && v == JsonToken.VALUE_STRING) {
                prompt.append(p.getText());
            } else if ("content".equals(name)) {
                collectInput(p, v, prompt, depth + 1);
            } else if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                p.skipChildren();
            }
        }
    }

    /** {@code usage.output_tokens} is authoritative; falls back to output text. */
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
        return ProtocolCodec.estimateTokens(outputText(root));
    }

    private static String outputText(JsonNode root) {
        StringBuilder text = new StringBuilder();
        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode block : content) {
                        JsonNode node = block.get("text");
                        if (node != null && node.isTextual()) {
                            text.append(node.asText());
                        }
                    }
                }
            }
        }
        return text.toString();
    }

    /** Only a completed response with real output may enter the semantic cache. */
    public static boolean cacheable(JsonNode root) {
        if (root == null || !root.isObject() || root.has("error")) {
            return false;
        }
        JsonNode output = root.get("output");
        return output != null && output.isArray() && !output.isEmpty();
    }
}
