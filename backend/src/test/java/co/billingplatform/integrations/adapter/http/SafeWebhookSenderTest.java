package co.billingplatform.integrations.adapter.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class SafeWebhookSenderTest {

    private static boolean pub(String ip) throws Exception {
        return SafeWebhookSender.isPublic(InetAddress.getByName(ip));
    }

    @Test
    @DisplayName("SSRF: rechaza loopback, privadas, link-local (metadatos), CGNAT, multicast y sus versiones IPv6")
    void blocksPrivateRanges() throws Exception {
        for (String ip : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "224.0.0.1", "198.18.0.1", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:127.0.0.1",
                "::ffff:10.0.0.1", "2001:db8::1"}) {
            assertFalse(pub(ip), ip);
        }
        for (String ip : new String[] {"8.8.8.8", "190.24.1.1", "172.32.0.1", "100.128.0.1", "2800:3f0:4005::200e"}) {
            assertTrue(pub(ip), ip);
        }
    }
}
