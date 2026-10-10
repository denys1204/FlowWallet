package com.flowwallet.payment.webhook;

import com.flowwallet.payment.config.PaymentWebhookProperties;
import com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.provider.exception.WebhookProcessingException;
import com.flowwallet.platform.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Connect webhook route through Spring MVC and the shared problem handler, with the service mocked.
 */
class ConnectWebhookControllerTest {
    private static final String PATH = "/api/payments/webhooks/stripe/connect";
    private static final String PLATFORM_PATH = "/api/payments/webhooks/stripe";
    private static final String PAYLOAD = "{\"id\":\"evt_1\",\"account\":\"acct_1\",\"note\":\"café\"}";

    private final ConnectWebhookService service = mock(ConnectWebhookService.class);
    private final PaymentWebhookService platformService = mock(PaymentWebhookService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PaymentWebhookProperties properties = new PaymentWebhookProperties();
        properties.setMaxPayloadSize(DataSize.ofBytes(1024));
        WebhookPayloadReader payloadReader = new WebhookPayloadReader(properties);

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ConnectWebhookController(service, payloadReader),
                        new WebhookController(platformService, payloadReader)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void theBodyAndHeadersReachTheServiceUnchanged() throws Exception {
        // The signature covers the exact bytes, so a body re-encoded on the way in would fail verification, and a
        // dropped Stripe-Signature header would refuse every delivery.
        mockMvc.perform(post(PATH)
                        .header("Stripe-Signature", "t=1,v1=00")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        verify(service).process(
                eq(PAYLOAD),
                argThat((Map<String, String> headers) -> headers.entrySet().stream().anyMatch(
                        entry -> entry.getKey().equalsIgnoreCase("Stripe-Signature")
                                && entry.getValue().equals("t=1,v1=00")
                ))
        );
    }

    @Test
    void aBadSignatureIs400() throws Exception {
        // Guards a forged delivery, or one with no Connect secret configured, being answered as anything but a 400
        // that names the signature.
        doThrow(new InvalidWebhookSignatureException("Invalid Stripe signature"))
                .when(service).process(anyString(), anyMap());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid Stripe signature"));
    }

    @Test
    void anOversizedBodyIs413AndNeverReachesTheService() throws Exception {
        // The route is public: without the cap, an unauthenticated body of any size is read whole before its
        // signature can be checked.
        byte[] oversized = new byte[1025];

        String body = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Webhook body exceeds 1024 bytes");
        verifyNoInteractions(service);
    }

    @Test
    void aProcessingFailureIs500() throws Exception {
        // Guards an event that could not be applied, such as a failed Stripe read, being answered 2xx, which would
        // make Stripe stop redelivering it.
        String message = "Stripe could not be read for Connect event evt_1";
        doThrow(new WebhookProcessingException(message, new RuntimeException()))
                .when(service).process(anyString(), anyMap());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(message));
    }

    @Test
    void theConnectPathNeverReachesTheProviderController() throws Exception {
        // Guards the platform route's /{provider} taking the Connect path, which would verify a Connect delivery
        // against the platform's secret and answer 400 to every one.
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isOk());

        verify(service).process(eq(PAYLOAD), anyMap());
        verify(platformService, never()).processWebhook(any(), any(), any());
    }

    @Test
    void thePlatformPathNeverReachesTheConnectController() throws Exception {
        // Guards the Connect route widening to the platform's path, which would verify a platform delivery against
        // the Connect secret.
        mockMvc.perform(post(PLATFORM_PATH).contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isOk());

        verify(platformService).processWebhook(eq("stripe"), eq(PAYLOAD), anyMap());
        verifyNoInteractions(service);
    }
}
