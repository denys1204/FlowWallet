package com.flowwallet.payment.webhook;

import com.flowwallet.payment.config.PaymentWebhookProperties;
import com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.provider.exception.UnsupportedPaymentProviderException;
import com.flowwallet.payment.provider.exception.WebhookProcessingException;
import com.flowwallet.platform.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The webhook route through Spring MVC and the shared problem handler, with the service mocked.
 */
class WebhookControllerTest {
    private static final String PATH = "/api/payments/webhooks/stripe";
    private static final String PAYLOAD = "{\"id\":\"evt_1\",\"note\":\"café\"}";

    private final PaymentWebhookService webhookService = mock(PaymentWebhookService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PaymentWebhookProperties properties = new PaymentWebhookProperties();
        properties.setMaxPayloadSize(DataSize.ofBytes(1024));
        WebhookController controller = new WebhookController(webhookService, new WebhookPayloadReader(properties));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void aWebhookIsHandedOnWithItsBodyUnchangedAndItsHeaders() throws Exception {
        // The signature covers the exact bytes, so a body re-encoded on the way in would fail verification.
        mockMvc.perform(post(PATH)
                        .header("Stripe-Signature", "t=1,v1=00")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        verify(webhookService).processWebhook(
                eq("stripe"),
                eq(PAYLOAD),
                argThat((Map<String, String> headers) -> headers.entrySet().stream().anyMatch(
                        entry -> entry.getKey().equalsIgnoreCase("Stripe-Signature")
                                && entry.getValue().equals("t=1,v1=00")
                ))
        );
    }

    @Test
    void aBadSignatureIsA400Problem() throws Exception {
        doThrow(new InvalidWebhookSignatureException("Invalid Stripe signature"))
                .when(webhookService).processWebhook(anyString(), anyString(), anyMap());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid Stripe signature"));
    }

    @Test
    void anUnknownProviderIsA400Problem() throws Exception {
        doThrow(new UnsupportedPaymentProviderException("Unsupported payment provider"))
                .when(webhookService).processWebhook(eq("paypal"), anyString(), anyMap());

        mockMvc.perform(post("/api/payments/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anEventThisSideCannotProcessIsA500Problem() throws Exception {
        doThrow(new WebhookProcessingException("Failed to deserialize Stripe event data", new RuntimeException()))
                .when(webhookService).processWebhook(anyString(), anyString(), anyMap());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void aBodyOverTheLimitIsA413ProblemAndNeverReachesTheService() throws Exception {
        // The route is public: without the cap, an unauthenticated body of any size is read whole before its
        // signature can be checked.
        byte[] oversized = new byte[1025];

        String detail = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(detail).contains("Webhook body exceeds 1024 bytes");
        verify(webhookService, never()).processWebhook(any(), any(), any());
    }

    @Test
    void aBodyExactlyAtTheLimitIsAccepted() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(new byte[1024]))
                .andExpect(status().isOk());
    }

    @Test
    void anUnreadableBodyIsA400Problem() throws Exception {
        // WebhookPayloadReaderTest only pins the exception type thrown on an I/O failure; this proves
        // GlobalExceptionHandler turns that exception into the 400 a caller actually sees.
        WebhookPayloadReader payloadReader = mock(WebhookPayloadReader.class);
        when(payloadReader.read(any())).thenThrow(new HttpMessageNotReadableException(
                "I/O error while reading the webhook body", (Throwable) null, null));
        MockMvc mvcWithUnreadableBody = MockMvcBuilders
                .standaloneSetup(new WebhookController(webhookService, payloadReader))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvcWithUnreadableBody.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
