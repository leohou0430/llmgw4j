package io.github.leohou0430.llmgw.control;

import io.github.leohou0430.llmgw.control.local.RestrictedNetworks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestrictedNetworksTest {

    @Test
    void matchesIpv4CidrInsideAndRejectsOutside() {
        RestrictedNetworks networks = new RestrictedNetworks("10.1.2.0/24, 192.168.5.7");

        assertTrue(networks.matches("10.1.2.3"));
        assertFalse(networks.matches("10.1.3.1"));
        assertTrue(networks.matches("192.168.5.7"));
        assertFalse(networks.matches("192.168.5.8"));
    }

    @Test
    void matchesExactIpv6AndMappedIpv4() {
        RestrictedNetworks networks = new RestrictedNetworks("2001:db8::1,10.9.0.0/16");

        assertTrue(networks.matches("2001:db8::1"));
        assertFalse(networks.matches("2001:db8::2"));
        assertTrue(networks.matches("::ffff:10.9.4.5"));
        assertFalse(networks.matches("::ffff:10.10.4.5"));
    }

    @Test
    void emptyAllowlistMatchesNothing() {
        RestrictedNetworks networks = new RestrictedNetworks(" ");

        assertFalse(networks.matches("127.0.0.1"));
        assertFalse(networks.matches(null));
    }

    @Test
    void invalidEntryFailsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new RestrictedNetworks("10.1.2.0/33"));
        assertThrows(IllegalArgumentException.class, () -> new RestrictedNetworks("not-an-address"));
    }
}
