package com.flowwallet.payment.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagementPortCheckTest {
    @ParameterizedTest(name = "management port \"{0}\" with server port {1}: separate {2}")
    @CsvSource(value = {
            "NONE, 8082, false",
            "'', 8082, false",
            "' ', 8082, false",
            "-1, 8082, false",
            "8082, 8082, false",
            "9090, 8082, true",
            "0, 8082, true",
            "8082, NONE, true"
    }, nullValues = "NONE")
    void onlyAPortOfItsOwnOpensASeparateManagementServer(String managementPort, String serverPort, boolean separate) {
        // Guards refusing the shipped setup, where the actuator shares the service's port, and letting through a
        // separate port, whose server listens on every interface without the request guard.
        assertThat(ManagementPortCheck.separate(managementPort, serverPort)).isEqualTo(separate);
    }

    @Test
    void aSeparateManagementPortFailsStartup() {
        // Guards the payouts writes reaching the network through a child server that ActuatorRequestGuard never sees.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.port", "8082")
                .withProperty("management.server.port", "9090");

        assertThatThrownBy(() -> new ManagementPortCheck(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("management.server.port must stay unset");
    }

    @Test
    void theShippedSetupStarts() {
        // Guards the check refusing a service whose actuator shares its port, which is every shipped configuration.
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "8082");

        assertThatCode(() -> new ManagementPortCheck(environment)).doesNotThrowAnyException();
    }
}
