package io.github.leohou0430.llmgw.app;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.10 seam (foundation): the control page is a same-process static resource (no Node)
 * served from the classpath and wired to the read-only /control/status surface.
 */
class ControlPageResourceTest {

    @Test
    void staticControlPageIsOnClasspathAndTargetsStatus() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/static/control/index.html")) {
            assertNotNull(in, "static control page must be packaged");
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(html.contains("/control/status"), "page must load the status surface");
            assertTrue(html.contains("/control/login"), "page must include the login shell");
            assertTrue(html.contains("/control/routes/publish"), "page must include route publishing");
            assertTrue(html.contains("X-CSRF-Token"), "mutating calls must carry the CSRF token");
            assertTrue(html.contains("<script"), "page is an interactive same-process asset");
        }
    }
}