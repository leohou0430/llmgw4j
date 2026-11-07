package io.github.leohou0430.llmgw.core.protocol;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

/**
 * OpenAI Images API ({@code POST /v1/images/generations}). Token accounting is
 * zero (billing is per image); the request is never cached because a response
 * body carries multi-hundred-KB base64 payloads.
 */
public final class ImagesProtocol {

    private static final int MAX_IMAGES = 10;

    private ImagesProtocol() {
    }

    public static ParsedRequest parse(String rawJson) {
        String model = "default";
        String prompt = "";
        int n = 1;
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
                } else if ("prompt".equals(name) && value == JsonToken.VALUE_STRING) {
                    prompt = p.getText();
                } else if ("n".equals(name) && value == JsonToken.VALUE_NUMBER_INT) {
                    n = Math.max(1, Math.min(MAX_IMAGES, p.getValueAsInt()));
                } else if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    p.skipChildren();
                }
            }
        }
        return new ParsedRequest(rawJson, ProtocolKind.IMAGES, model, false, prompt,
                0, n, null);
    }
}
