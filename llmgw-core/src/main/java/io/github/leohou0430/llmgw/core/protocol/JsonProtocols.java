package io.github.leohou0430.llmgw.core.protocol;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

/**
 * W4.3 protocol-surface expansion: the three non-streaming JSON/binary
 * protocols that share one minimal probe shape (model + one text field).
 * Same pass-through contract as every other kind: only what governance needs
 * is extracted, everything else forwards byte-for-byte. None of them cache,
 * none of them stream, and completion tokens are always 0 (their responses
 * carry no usage frames the gateway bills).
 */
public final class JsonProtocols {

    private JsonProtocols() {
    }

    public static ParsedRequest parse(ProtocolKind kind, String rawJson, String textField) {
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
                } else if (value == JsonToken.START_ARRAY) {
                    // Text-bearing arrays (embeddings input, rerank documents):
                    // every string element feeds the estimate.
                    collectArrayStrings(p, text);
                } else if (textField.equals(name) && value == JsonToken.VALUE_STRING) {
                    text.append(p.getText());
                } else if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    p.skipChildren();
                }
            }
        }
        String extracted = text.toString();
        return new ParsedRequest(rawJson, kind, model, false, extracted,
                ProtocolCodec.estimateTokens(extracted), 1, null);
    }

    private static void collectArrayStrings(JsonParser p, StringBuilder text) {
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
            if (t == JsonToken.VALUE_STRING) {
                text.append(p.getText());
            } else if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
                p.skipChildren();
            }
        }
    }
}
