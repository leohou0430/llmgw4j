package io.github.leohou0430.llmgw.provider.openai.sdk;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.leohou0430.llmgw.provider.ProviderVerifier;

/**
 * Official OpenAI Java SDK adapter.
 *
 * <p>IMPORTANT: by design this class is NOT on the hot forwarding path. The gateway
 * forwards raw SSE/JSON bytes itself so vendor-specific fields are never lost. The SDK
 * is used only for typed request validation and an optional real-provider connectivity
 * check against OpenAI-compatible endpoints (e.g. Alibaba DashScope).
 */
public class OpenAiSdkVerifier implements ProviderVerifier {

    /** Build a typed SDK request to validate shape without touching the network. */
    public boolean validate(String model, String prompt) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model)
                .addUserMessage(prompt)
                .build();
        return params != null;
    }

    /** Optional end-to-end connectivity check. Never called automatically at startup. */
    public String verify(GatewayProperties properties) {
        String apiKey = properties.getUpstream().getApiKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            return "skipped: no api key configured";
        }
        // OpenAIClient has close() but does NOT extend AutoCloseable. Each call
        // used to build a fresh OkHttp client (connection pool + dispatcher
        // threads) and leak it; /admin/verify is now rate-gated AND closes it.
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(properties.getUpstream().getBaseUrl())
                .apiKey(apiKey)
                .checkJacksonVersionCompatibility(false)
                .build();
        try {
            return "models endpoint reachable: " + (client.models().list() != null);
        } catch (RuntimeException e) {
            return "connectivity check failed: " + e.getClass().getSimpleName();
        } finally {
            client.close();
        }
    }
}
