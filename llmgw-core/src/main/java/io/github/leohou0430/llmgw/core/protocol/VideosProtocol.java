package io.github.leohou0430.llmgw.core.protocol;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;

/**
 * OpenAI Videos API ({@code POST /v1/videos}, Sora-style async jobs). Task
 * polling and content download are bodyless GETs ({@link #bodyless}); only the
 * create call parses a body and bills one task unit. Video tasks are NEVER
 * cached: every call creates a new upstream job.
 */
public final class VideosProtocol {

    private VideosProtocol() {
    }

    public static ParsedRequest parse(String rawJson) {
        String model = "default";
        String prompt = "";
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
                } else if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    p.skipChildren();
                }
            }
        }
        return new ParsedRequest(rawJson, ProtocolKind.VIDEOS, model, false, prompt,
                0, 1, null);
    }

    /** The task id of a create/poll response; empty when the body has none. */
    public static String taskId(JsonNode root) {
        if (root == null) {
            return "";
        }
        JsonNode id = root.get("id");
        return id != null && id.isTextual() ? id.asText() : "";
    }

    public static String status(JsonNode root) {
        if (root == null) {
            return "";
        }
        JsonNode status = root.get("status");
        return status != null && status.isTextual() ? status.asText() : "";
    }
}
