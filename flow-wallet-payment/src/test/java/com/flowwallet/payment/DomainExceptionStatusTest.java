package com.flowwallet.payment;

import com.flowwallet.payment.provider.exception.InvalidPaymentRequestException;
import com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.provider.exception.PaymentInitiationException;
import com.flowwallet.payment.provider.exception.UnsupportedPaymentProviderException;
import com.flowwallet.payment.provider.exception.WebhookProcessingException;
import com.flowwallet.payment.transaction.DuplicateTransactionReferenceException;
import com.flowwallet.payment.transaction.TransactionNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the HTTP status each domain exception carries, independently of the shared render path, so a
 * mis-wired status (e.g. a copy-paste to the wrong HttpStatus) is caught here rather than only at the API boundary.
 */
class DomainExceptionStatusTest {

    @Test
    void domainExceptionsCarryTheirDeclaredHttpStatus() {
        assertThat(new PaymentInitiationException("provider down", new RuntimeException()).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(new InvalidPaymentRequestException("bad request").getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new InvalidWebhookSignatureException("bad signature").getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new UnsupportedPaymentProviderException("unknown provider").getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new TransactionNotFoundException("missing").getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(new WebhookProcessingException("processing failed", new RuntimeException()).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(DuplicateTransactionReferenceException.forReference("ref-1").getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}
