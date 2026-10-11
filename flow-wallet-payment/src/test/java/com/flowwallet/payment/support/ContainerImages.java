package com.flowwallet.payment.support;

/**
 * The images the integration tests run: the tags docker-compose.yml starts for local development, so a test never
 * passes against a database or broker version the stack does not use. {@code ContainerImagesTest} compares the two.
 */
public final class ContainerImages {
    public static final String POSTGRES = "postgres:17-alpine";

    public static final String KAFKA = "apache/kafka:4.2.1";

    private ContainerImages() {
    }
}
