package com.flowwallet.payment.provider.exception;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a requested payment provider is unknown or has no registered strategy.
 * Maps to HTTP 400 Bad Request (the caller supplied an unsupported provider name).
 */
public class UnsupportedPaymentProviderException extends ApiException {
    public UnsupportedPaymentProviderException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
