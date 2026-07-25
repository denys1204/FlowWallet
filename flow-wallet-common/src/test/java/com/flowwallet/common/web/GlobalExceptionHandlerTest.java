package com.flowwallet.common.web;

import com.flowwallet.common.exception.ApiException;
import com.flowwallet.common.security.MissingUserIdException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsApiExceptionToItsDeclaredStatusAndSafeProblemDetail() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallets/1");

        ProblemDetail body =
                handler.handleApiException(new MissingUserIdException("Missing required header: X-User-Id"), request);

        assertThat(body.getStatus()).isEqualTo(401);
        assertThat(body.getTitle()).isEqualTo(HttpStatus.UNAUTHORIZED.getReasonPhrase());
        assertThat(body.getDetail()).isEqualTo("Missing required header: X-User-Id");
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/wallets/1"));
        assertThat(body.getProperties()).containsKey("timestamp");
    }

    @Test
    void usesTheStatusCarriedByEachApiExceptionSubtype() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments/intent");

        ProblemDetail body = handler.handleApiException(new NotFoundTestException("not here"), request);

        assertThat(body.getStatus()).isEqualTo(404);
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/payments/intent"));
    }

    @Test
    void fallsBackToGeneric500WithoutLeakingInternalDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/boom");

        ProblemDetail body = handler.handleUnexpected(new IllegalStateException("sensitive stack detail"), request);

        assertThat(body.getStatus()).isEqualTo(500);
        assertThat(body.getDetail()).isEqualTo("Internal server error");
        assertThat(body.getDetail()).doesNotContain("sensitive");
    }

    /** Local subtype to prove the handler honours whatever status an ApiException declares. */
    private static final class NotFoundTestException extends ApiException {
        private NotFoundTestException(String message) {
            super(HttpStatus.NOT_FOUND, message);
        }
    }
}
