package com.flowwallet.payment.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerImagesTest {
    @Test
    void theIntegrationTestsRunTheImagesDockerComposeStarts() throws IOException {
        // Guards a Postgres or Kafka upgrade in docker-compose.yml that the integration tests never see, so they keep
        // passing against the old version. Surefire runs in the module directory, so the file is one level up.
        String compose = Files.readString(Path.of("..", "docker-compose.yml"));

        assertThat(compose)
                .contains("image: " + ContainerImages.POSTGRES)
                .contains("image: " + ContainerImages.KAFKA);
    }
}
