package io.github.leohou0430.llmgw.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GatewayPropertiesAdminTokenTest {

    @Test
    void legacyTokenModeDefaultsToDisabled() {
        assertEquals("disabled", new GatewayProperties().getAdminTokenMode());
    }

    @Test
    void migrationModeIsExplicitAndNormalized() {
        GatewayProperties properties = new GatewayProperties();

        properties.setAdminTokenMode(" MIGRATION ");

        assertEquals("migration", properties.getAdminTokenMode());
    }

    @Test
    void unknownModeIsRejected() {
        GatewayProperties properties = new GatewayProperties();

        assertThrows(IllegalArgumentException.class, () -> properties.setAdminTokenMode("enabled"));
    }
}
