package com.flowwallet.payment.config;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup when the actuator would run on a separate management port. Boot starts a child server for it, which
 * listens on every interface whatever {@code server.address} says, and runs without {@link ActuatorRequestGuard}, a
 * filter of the main context, so the unauthenticated writes of the {@code payouts} endpoint would reach the network.
 * See docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
 */
@Component
public class ManagementPortCheck {
    public ManagementPortCheck(Environment environment) {
        if (separate(environment.getProperty("management.server.port"), environment.getProperty("server.port"))) {
            throw new IllegalStateException(
                    "management.server.port must stay unset: a separate management server would listen on every "
                            + "interface and run without ActuatorRequestGuard"
            );
        }
    }

    /**
     * Whether a management port opens a server of its own: any value but none, the service's own port, or -1, which
     * turns the actuator off over HTTP.
     */
    static boolean separate(@Nullable String managementPort, @Nullable String serverPort) {
        return managementPort != null
                && !managementPort.isBlank()
                && !managementPort.equals("-1")
                && !managementPort.equals(serverPort);
    }
}
