package com.flowwallet.gateway.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * Warns at startup when the gateway listens beyond loopback. The gateway forwards {@code X-User-Id} as the client
 * sent it, so anyone who reaches a wider address and knows a user id acts as that user. Listening wider is right
 * only behind an authentication layer that sets the header, and the warning makes the operator confirm it.
 * See docs/adr/0031-callers-are-authenticated-in-front-of-the-gateway.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BindAddressCheck {
    private final ServerProperties server;

    @EventListener(ApplicationReadyEvent.class)
    public void warnWhenReachableBeyondLoopback() {
        InetAddress address = server.getAddress();
        if (address != null && address.isLoopbackAddress()) {
            return;
        }
        log.warn(
                "The gateway listens on {}, not only on loopback. It forwards X-User-Id as the client sent it, so it "
                        + "must sit behind an authentication layer that sets the header (GATEWAY_ADDRESS)",
                address == null ? "every interface" : address.getHostAddress()
        );
    }
}
