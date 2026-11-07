package io.github.leohou0430.llmgw.core.protocol;

/**
 * The protocol an endpoint speaks. Pass-through parity: a route with protocol P
 * is only ever sent upstreams that also speak P, and the gateway never
 * translates between kinds.
 *
 * <p>{@link #upstreamPath} is the path appended to a route's base URL;
 * {@link #terminator} is the name of the streaming protocol terminator, used in
 * truncation error messages so the wording is identical across both engines.
 */
public enum ProtocolKind {

    CHAT("/chat/completions", true, "[DONE]"),
    RESPONSES("/responses", true, "response.completed"),
    MESSAGES("/messages", true, "message_stop"),
    IMAGES("/images/generations", false, ""),
    VIDEOS("/videos", false, "");

    private final String upstreamPath;
    private final boolean streamCapable;
    private final String terminator;

    ProtocolKind(String upstreamPath, boolean streamCapable, String terminator) {
        this.upstreamPath = upstreamPath;
        this.streamCapable = streamCapable;
        this.terminator = terminator;
    }

    /** Path appended to a route's targetUrl when forwarding upstream. */
    public String upstreamPath() {
        return upstreamPath;
    }

    /** Wire name used in metrics tags and audit events. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean streamCapable() {
        return streamCapable;
    }

    /** Streaming terminator name; empty for the non-streaming kinds. */
    public String terminator() {
        return terminator;
    }

    /** Parses a route/protocol config value; "" and "*" mean protocol-agnostic. */
    public static boolean isConfigValue(String value) {
        return value == null || value.isBlank() || "*".equals(value)
                || java.util.Arrays.stream(values())
                        .anyMatch(k -> k.wireName().equals(value));
    }
}
