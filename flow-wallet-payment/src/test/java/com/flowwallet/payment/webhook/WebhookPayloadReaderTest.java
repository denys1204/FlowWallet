package com.flowwallet.payment.webhook;

import com.flowwallet.payment.config.PaymentWebhookProperties;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.unit.DataSize;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookPayloadReaderTest {
    private static final int LIMIT = 16;

    private final WebhookPayloadReader reader = new WebhookPayloadReader(propertiesWithLimit());

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedWithoutReadingTheBody() throws Exception {
        // Reading first would let a client make the service buffer whatever it declares.
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(LIMIT + 1L);

        assertThatThrownBy(() -> reader.read(request)).isInstanceOf(WebhookPayloadTooLargeException.class);
        verify(request, never()).getInputStream();
    }

    @Test
    void aChunkedBodyOverTheLimitIsRefusedWhileItIsRead() {
        // A chunked request declares no Content-Length, so the length check alone would let it through.
        HttpServletRequest request = chunked(new byte[LIMIT + 1]);

        assertThatThrownBy(() -> reader.read(request)).isInstanceOf(WebhookPayloadTooLargeException.class);
    }

    @Test
    void aChunkedBodyWithinTheLimitIsReadWhole() {
        // Guards against the length check alone swallowing a legitimate chunked webhook.
        assertThat(reader.read(chunked("{\"id\":\"evt_1\"}".getBytes()))).isEqualTo("{\"id\":\"evt_1\"}");
    }

    @Test
    void anIoFailureWhileReadingIsWrappedAsTheSameExceptionAnUnreadableRequestBodyThrows() throws Exception {
        // This class has no Spring MVC context to render a status from, so it only pins the exception type;
        // WebhookControllerTest#anUnreadableBodyIsA400Problem asserts the 400 that GlobalExceptionHandler
        // gives HttpMessageNotReadableException end to end.
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletInputStream input = mock(ServletInputStream.class);
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getInputStream()).thenReturn(input);
        when(input.readNBytes(LIMIT + 1)).thenThrow(new IOException("connection reset"));

        assertThatThrownBy(() -> reader.read(request)).isInstanceOf(HttpMessageNotReadableException.class);
    }

    private static HttpServletRequest chunked(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments/webhooks/stripe");
        request.setContent(body);
        return new HttpServletRequestWrapper(request) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
    }

    private static PaymentWebhookProperties propertiesWithLimit() {
        PaymentWebhookProperties properties = new PaymentWebhookProperties();
        properties.setMaxPayloadSize(DataSize.ofBytes(LIMIT));
        return properties;
    }
}
