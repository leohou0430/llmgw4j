package io.github.leohou0430.llmgw.core.protocol;

import java.nio.charset.StandardCharsets;

/**
 * Minimal multipart/form-data probe (W4.3): finds ONE named form field's
 * value in a raw multipart body without parsing the whole structure. Used by
 * the byte-body protocols (audio transcriptions, file uploads) to extract the
 * routing-relevant fields — the body itself always passes through
 * byte-for-byte and is never rebuilt.
 *
 * <p>Scan contract: a field appears as {@code name="<name>"} inside a part's
 * Content-Disposition header; the value starts after the following CRLFCRLF
 * and ends at the next CRLF (the closing boundary stays untouched).
 */
public final class MultipartProbe {

    private static final byte CR = 13;
    private static final byte LF = 10;

    private MultipartProbe() {
    }

    public static String field(byte[] body, String name) {
        if (body == null || body.length == 0 || name == null || name.isBlank()) {
            return "";
        }
        byte[] needle = ("name=\"" + name + "\"").getBytes(StandardCharsets.US_ASCII);
        int at = indexOf(body, needle, 0);
        while (at >= 0) {
            // Value starts after the part's header/body separator.
            int valueStart = indexOf(body, new byte[]{CR, LF, CR, LF}, at);
            if (valueStart < 0) {
                return "";
            }
            valueStart += 4;
            int valueEnd = valueStart;
            while (valueEnd + 1 < body.length
                    && !(body[valueEnd] == CR && body[valueEnd + 1] == LF)) {
                valueEnd++;
            }
            return new String(body, valueStart, valueEnd - valueStart, StandardCharsets.UTF_8)
                    .strip();
        }
        return "";
    }

    /** Rough body-proportional estimate; multipart bodies are never cached. */
    public static int estimateTokens(byte[] body) {
        return body == null ? 1 : Math.max(1, body.length / 4);
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        if (pattern.length == 0 || data.length < pattern.length) {
            return -1;
        }
        outer:
        for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
