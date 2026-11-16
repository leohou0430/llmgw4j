package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.conversion.AppliedDegradation;
import io.github.leohou0430.llmgw.conversion.CapabilityContext;
import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ContinuationEnvelope;
import io.github.leohou0430.llmgw.conversion.DegradationAudit;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.EntryCodec;
import io.github.leohou0430.llmgw.conversion.ImagePart;
import io.github.leohou0430.llmgw.conversion.Role;
import io.github.leohou0430.llmgw.conversion.SemanticMessage;
import io.github.leohou0430.llmgw.conversion.SemanticRequest;
import io.github.leohou0430.llmgw.conversion.ThinkingPart;
import io.github.leohou0430.llmgw.conversion.ToolResultPart;
import io.github.leohou0430.llmgw.conversion.ToolSpec;
import io.github.leohou0430.llmgw.conversion.Violation;
import io.github.leohou0430.llmgw.conversion.chat.ChatEntryCodec;
import io.github.leohou0430.llmgw.conversion.messages.MessagesEntryCodec;
import io.github.leohou0430.llmgw.conversion.responses.ResponsesEntryCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.13b: every acceptance fixture request must decode through the REAL entry codec into a legal
 * SemanticRequest (wire-format exactness, not mere JSON parsing). Continuation fixtures carry
 * deterministic pre-minted envelope tokens verified by the real {@link ContinuationEnvelope}.
 */
class AcceptanceFixtureWireFormatTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PROBE_SHA256 =
            "f24b0899ce1bb9147836beb6812c9ab7778a864f39c44b14d32548e4d91228d9";
    private static final List<String> SLUGS = List.of(
            "text-multiturn", "stream-text-usage", "tool-call-continuation",
            "thinking-continuation", "image-understanding", "cancel-recover", "contract-negatives");
    private static final Path FIXTURES = fixturesDir();
    private static final JsonNode MANIFEST = readJson(FIXTURES.resolve("manifest.json"));
    private static final JsonNode ENVELOPE = MANIFEST.path("continuation_envelope");

    private final ChatEntryCodec chat = new ChatEntryCodec();
    private final ResponsesEntryCodec responses = new ResponsesEntryCodec();
    private final MessagesEntryCodec messages = new MessagesEntryCodec();

    private static Path fixturesDir() {
        Path base = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path fromModule = base.resolve("../tools/acceptance/stage1/fixtures").normalize();
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        return base.resolve("tools/acceptance/stage1/fixtures").normalize();
    }

    private static JsonNode readJson(Path path) {
        try {
            return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("missing fixture file: " + path, e);
        }
    }

    private static CapabilityContext ctx() {
        return new CapabilityContext(
                ENVELOPE.path("tenant_id").asString(),
                ENVELOPE.path("project_id").asString(),
                new DegradationAudit(DegradationGrant.empty()),
                new ContinuationEnvelope(ENVELOPE.path("key_utf8").asString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void chatSixScenariosDecodeWithRealCodec() {
        decodeEntry("chat", chat);
    }

    @Test
    void responsesSixScenariosDecodeWithRealCodec() {
        decodeEntry("responses", responses);
    }

    @Test
    void messagesSixScenariosDecodeWithRealCodec() {
        decodeEntry("messages", messages);
    }

    @Test
    void manifestIndexCoversThreeEntriesSevenScenarios() {
        JsonNode index = MANIFEST.path("index");
        assertEquals(21, index.size(), "manifest index must cover 3 entries x 7 scenarios");
        Set<String> pairs = new java.util.HashSet<>();
        for (JsonNode row : index) {
            pairs.add(row.path("entry").asString() + "/" + row.path("scenario").intValue());
            assertTrue(Files.isRegularFile(FIXTURES.resolve(row.path("file").asString())),
                    "indexed fixture missing: " + row.path("file").asString());
        }
        assertEquals(21, pairs.size());
    }

    @Test
    void chatContractNegativesRejectAndTheAuthorizedDegradationIsAudited() {
        decodeNegativeEntry("chat", chat);
    }

    @Test
    void responsesContractNegativesRejectAndTheAuthorizedDegradationIsAudited() {
        decodeNegativeEntry("responses", responses);
    }

    @Test
    void messagesContractNegativesRejectAndTheAuthorizedDegradationIsAudited() {
        decodeNegativeEntry("messages", messages);
    }

    /**
     * s7 drives the REAL controllers, so the negatives are pinned against the DRIVE key/project
     * (manifest.continuation_envelope.drive_*), not the s4 fixture envelope: a cross-project token must
     * pass HMAC and fail on project binding, which is only reachable with the drive's own key.
     */
    private static CapabilityContext driveCtx(DegradationGrant grant) {
        String project = ENVELOPE.path("drive_project_id").asString();
        return new CapabilityContext(project, project, new DegradationAudit(grant),
                new ContinuationEnvelope(
                        ENVELOPE.path("drive_key_utf8").asString().getBytes(StandardCharsets.UTF_8)));
    }

    private void decodeNegativeEntry(String entry, EntryCodec codec) {
        JsonNode fixture = readJson(FIXTURES.resolve(entry + "/s7-" + SLUGS.get(6) + ".json"));
        assertEquals(entry, fixture.path("entry").asString());
        assertEquals(7, fixture.path("scenario").intValue());
        int negatives = 0;
        int authorized = 0;
        for (JsonNode step : fixture.path("steps")) {
            String label = entry + " s7 " + step.path("name").asString();
            JsonNode request = step.path("request");
            String raw = MAPPER.writeValueAsString(request);
            JsonNode error = step.path("expected").path("error");
            if (error.isMissingNode()) {
                authorized++;
                JsonNode degradation = step.path("degradation");
                String value = grantValue(step.path("headers"), degradation.path("header").asString(), label);
                CapabilityContext context = driveCtx(DegradationGrant.parse(value));
                SemanticRequest decoded = codec.decode(raw, context);
                assertEquals("mock-model", decoded.model(), label);
                assertFalse(decoded.messages().isEmpty(), label);
                List<AppliedDegradation> applied = context.audit().applied();
                assertEquals(1, applied.size(), label + ": the authorized degradation must be audited");
                assertEquals(degradation.path("id").asString(), applied.get(0).degradationId(), label);
                assertEquals(degradation.path("field_path").asString(), applied.get(0).fieldPath(), label);
                assertFalse(applied.get(0).reason().isBlank(), label + ": the audit must carry a reason");
                assertScenarioSemantics(entry, 7, step.path("name").asString(), decoded, request, label);
                continue;
            }
            negatives++;
            CapabilityContext context = driveCtx(DegradationGrant.empty());
            CapabilityException failure = assertThrows(CapabilityException.class, () -> codec.decode(raw, context),
                    label + ": the real codec must reject this controlled contract negative");
            assertEquals(error.path("status").intValue(), failure.status(), label);
            assertEquals(1, failure.violations().size(), label);
            Violation violation = failure.violations().get(0);
            assertEquals(error.path("code").asString(), violation.code(), label + ": stable capability code");
            assertEquals(error.path("param").asString(), violation.fieldPath(), label + ": field path");
            assertEquals(error.path("reason").asString(), violation.reason(), label + ": reason");
            assertTrue(context.audit().applied().isEmpty(), label + ": a rejection must not record a degradation");
        }
        assertEquals(fixture.path("steps").size() - 1, negatives,
                entry + " s7: every step except the authorized positive must be a negative");
        assertEquals(1, authorized, entry + " s7: exactly one authorized positive");
    }

    private static String grantValue(JsonNode headers, String header, String label) {
        for (var field : headers.properties()) {
            if (field.getKey().equalsIgnoreCase(header)) {
                return field.getValue().asString();
            }
        }
        throw new IllegalStateException(label + ": no " + header + " grant header in " + headers);
    }

    @Test
    void continuationEnvelopeTokensVerifyAndTamperIsRejected() {
        ContinuationEnvelope envelope = new ContinuationEnvelope(
                ENVELOPE.path("key_utf8").asString().getBytes(StandardCharsets.UTF_8));
        String project = ENVELOPE.path("project_id").asString();
        String reasoning = ENVELOPE.path("reasoning_text").asString();
        for (String entry : List.of("responses", "messages")) {
            JsonNode continuation = readJson(FIXTURES.resolve(entry + "/s4-thinking-continuation.json"))
                    .path("steps").get(1).path("request");
            String token = extractToken(entry, continuation);
            assertDoesNotThrow(() -> envelope.verify(token, project, reasoning),
                    entry + " fixture token must verify under the documented fixture key/project");
            int dot = token.lastIndexOf('.');
            char first = token.charAt(dot + 1);
            String tampered = token.substring(0, dot + 1)
                    + (first == 'A' ? 'B' : 'A') + token.substring(dot + 2);
            CapabilityException failure = assertThrows(CapabilityException.class,
                    () -> envelope.verify(tampered, project, reasoning));
            assertEquals(400, failure.status());
            assertEquals(CapabilityException.CONTINUATION_INVALID, failure.violations().get(0).code());
        }
    }

    private static String extractToken(String entry, JsonNode request) {
        if ("responses".equals(entry)) {
            for (JsonNode item : request.path("input")) {
                if ("reasoning".equals(item.path("type").asString())) {
                    return item.path("encrypted_content").asString();
                }
            }
        } else {
            for (JsonNode message : request.path("messages")) {
                if (!"assistant".equals(message.path("role").asString())) {
                    continue;
                }
                for (JsonNode block : message.path("content")) {
                    if ("thinking".equals(block.path("type").asString())) {
                        return block.path("signature").asString();
                    }
                }
            }
        }
        throw new IllegalStateException("no continuation token found in " + entry + " s4 continuation");
    }

    private void decodeEntry(String entry, EntryCodec codec) {
        for (int scenario = 1; scenario <= 6; scenario++) {
            Path file = FIXTURES.resolve(entry + "/s" + scenario + "-" + SLUGS.get(scenario - 1) + ".json");
            JsonNode fixture = readJson(file);
            assertEquals(entry, fixture.path("entry").asString());
            assertEquals(scenario, fixture.path("scenario").intValue());
            JsonNode steps = fixture.path("steps");
            assertTrue(steps.isArray() && !steps.isEmpty());
            for (JsonNode step : steps) {
                String label = entry + " s" + scenario + " " + step.path("name").asString();
                JsonNode request = step.path("request");
                assertTrue(request.isObject(), label + ": request must be a JSON object");
                CapabilityContext context = ctx();
                SemanticRequest decoded = codec.decode(MAPPER.writeValueAsString(request), context);
                assertEquals("mock-model", decoded.model(), label);
                assertFalse(decoded.messages().isEmpty(), label);
                boolean stream = "sse".equals(step.path("expected").path("transport").asString());
                assertEquals(stream, decoded.stream(), label + ": stream flag must match expected transport");
                assertTrue(context.audit().applied().isEmpty(),
                        label + ": fixtures must decode without any degradation grant");
                assertScenarioSemantics(entry, scenario, step.path("name").asString(), decoded, request, label);
            }
        }
    }

    private void assertScenarioSemantics(String entry, int scenario, String stepName,
                                         SemanticRequest decoded, JsonNode request, String label) {
        switch (scenario) {
            case 1 -> {
                assertTrue(decoded.messages().size() >= 3, label + ": multi-turn history expected");
                assertEquals(Role.USER, decoded.messages().get(decoded.messages().size() - 1).role(), label);
            }
            case 2 -> {
                assertTrue(decoded.stream(), label);
                if ("chat".equals(entry)) {
                    assertEquals(Boolean.TRUE, decoded.includeUsage(), label + ": chat usage chunk requested");
                }
            }
            case 3 -> {
                Set<String> names = decoded.tools().stream().map(ToolSpec::name).collect(Collectors.toSet());
                assertEquals(Set.of("get_weather", "add_numbers"), names, label);
                assertEquals("auto", decoded.toolChoice(), label);
                if ("continuation".equals(stepName)) {
                    List<ToolResultPart> results = decoded.messages().stream()
                            .flatMap(m -> m.parts().stream())
                            .filter(ToolResultPart.class::isInstance)
                            .map(ToolResultPart.class::cast)
                            .toList();
                    assertEquals(1, results.size(), label + ": exactly one tool result continuation");
                    assertEquals("call_mock_1", results.get(0).toolCallId(), label);
                    assertFalse(results.get(0).isError(), label);
                    assertTrue(results.get(0).content().contains("\"temperature_c\":18"), label);
                }
            }
            case 4 -> {
                assertEquals(Boolean.TRUE, decoded.thinkingEnabled(), label);
                if ("continuation".equals(stepName)) {
                    List<ThinkingPart> thinking = decoded.messages().stream()
                            .flatMap(m -> m.parts().stream())
                            .filter(ThinkingPart.class::isInstance)
                            .map(ThinkingPart.class::cast)
                            .toList();
                    assertEquals(1, thinking.size(), label + ": carried reasoning state expected");
                    assertEquals("Let me think", thinking.get(0).reasoning(), label);
                    if ("chat".equals(entry)) {
                        assertNull(thinking.get(0).continuationToken(), label + ": chat is native plaintext");
                    } else {
                        assertNotNull(thinking.get(0).continuationToken(), label);
                        assertTrue(thinking.get(0).continuationToken().startsWith("llmgw1:"), label);
                    }
                }
            }
            case 5 -> {
                List<ImagePart> images = decoded.messages().stream()
                        .flatMap(m -> m.parts().stream())
                        .filter(ImagePart.class::isInstance)
                        .map(ImagePart.class::cast)
                        .toList();
                assertEquals(1, images.size(), label);
                assertEquals("image/png", images.get(0).mediaType(), label);
                byte[] bytes = Base64.getDecoder().decode(images.get(0).base64Data());
                assertEquals(PROBE_SHA256, sha256Hex(bytes), label + ": inline probe bytes must be the pinned PNG");
            }
            case 6 -> {
                assertTrue(decoded.stream(), label);
                if ("cancel".equals(stepName)) {
                    assertTrue(MAPPER.writeValueAsString(request).contains("mock-stall-mid-"),
                            label + ": cancel bait marker expected");
                }
            }
            case 7 -> {
                // Only the authorized positive reaches here; the semantic effect of the degradation.
                assertEquals("degradation-authorized", stepName, label);
                switch (entry) {
                    case "chat" -> assertEquals("json_object", decoded.responseFormat(),
                            label + ": json_schema must degrade to json_object");
                    case "responses" -> assertEquals("auto", decoded.toolChoice(),
                            label + ": required must degrade to auto in thinking mode");
                    case "messages" -> {
                        assertEquals(Boolean.TRUE, decoded.thinkingEnabled(), label);
                        assertEquals("low", decoded.thinkingEffort(),
                                label + ": budget_tokens 1024 must degrade to the low effort tier");
                    }
                    default -> throw new IllegalStateException("unknown entry " + entry);
                }
            }
            default -> throw new IllegalStateException("unknown scenario " + scenario);
        }
    }
}