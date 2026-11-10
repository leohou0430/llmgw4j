package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.AuditSink;
import io.github.leohou0430.llmgw.core.governance.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdminAuthorizationTest {
    private int loads;

    private AdminController controller(String fullToken, String readToken) {
        var properties = new GatewayProperties();
        properties.setAdminToken(fullToken);
        properties.setAdminReadToken(readToken);
        var beans = new DefaultListableBeanFactory();
        SnapshotStore snapshots = new SnapshotStore() {
            public void save() { }
            public void importJson(String json) { }
            public void load() { loads++; }
            public String exportJson() { return "{}"; }
        };
        return new AdminController(null, snapshots, null, null,
                null, null, null, null, beans.getBeanProvider(AuditSink.class),
                null, null, null, properties);
    }

    private void unauthorized(Runnable action) {
        var failure = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(401, failure.getStatusCode().value());
    }

    @Test void auditRejectsMissingBlankAndInvalidCredentialsWith401() {
        var admin = controller("full-secret", "read-secret");
        for (String token : new String[]{null, "", " ", "invalid"}) {
            unauthorized(() -> admin.exportAudit(token, null));
            unauthorized(() -> admin.exportAudit(null, token));
        }
    }

    @Test void auditAcceptsFullTokenAndReadTokenInTheirDocumentedHeaders() {
        var admin = controller("full-secret", "read-secret");
        assertEquals(List.of(), admin.exportAudit("full-secret", null));
        assertEquals(List.of(), admin.exportAudit(null, "read-secret"));
    }

    @Test void readOnlyConfigurationDoesNotEnableAnonymousAudit() {
        var admin = controller("", "read-secret");
        unauthorized(() -> admin.exportAudit(null, null));
        unauthorized(() -> admin.exportAudit(null, "invalid"));
        assertEquals(List.of(), admin.exportAudit(null, "read-secret"));
    }

    @Test void allOtherAdminEndpointsRejectAnonymousInvalidAndReadOnlyTokens() throws Exception {
        for (String full : new String[]{"full-secret", ""}) {
            var admin = controller(full, "read-secret");
            for (var method : AdminController.class.getDeclaredMethods()) {
                boolean endpoint = method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class);
                if (!endpoint || method.getName().equals("console") || method.getName().equals("exportAudit")) continue;
                for (String token : new String[]{null, "", "invalid", "read-secret"}) {
                    Object[] arguments = new Object[method.getParameterCount()];
                    arguments[0] = token;
                    for (int i = 1; i < arguments.length; i++) {
                        if (method.getParameterTypes()[i] == int.class) arguments[i] = 1;
                    }
                    var failure = assertThrows(InvocationTargetException.class,
                            () -> method.invoke(admin, arguments), method.getName());
                    var status = assertInstanceOf(ResponseStatusException.class, failure.getCause(), method.getName());
                    assertEquals(401, status.getStatusCode().value(), method.getName());
                }
            }
        }
        assertEquals(0, loads, "rejected writes must have no effect");
    }

    @Test void fullTokenCanWriteWhileReadOnlyTokenCannot() {
        var admin = controller("full-secret", "read-secret");
        unauthorized(() -> admin.load("read-secret"));
        assertEquals(0, loads);
        admin.load("full-secret");
        assertEquals(1, loads);
    }

    @Test void explicitlyUnconfiguredDemoRemainsOpen() {
        var admin = controller("", "");
        assertEquals(List.of(), admin.exportAudit(null, null));
        admin.load(null);
        assertEquals(1, loads);
    }
}
