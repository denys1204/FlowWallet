package com.flowwallet.payment.transaction;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when no payment transaction matches the given reference/provider id. Maps to HTTP 404 Not Found.
 */
public class TransactionNotFoundException extends ApiException {
    public TransactionNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
