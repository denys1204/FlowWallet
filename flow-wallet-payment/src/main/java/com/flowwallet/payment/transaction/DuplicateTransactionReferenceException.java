package com.flowwallet.payment.transaction;

import com.flowwallet.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a transaction reference is already in use — either won by a concurrent request or owned by a
 * different user. Maps to HTTP 409 Conflict.
 */
public class DuplicateTransactionReferenceException extends ApiException {
    private DuplicateTransactionReferenceException(String message) {
        super(HttpStatus.CONFLICT, message);
    }

    public static DuplicateTransactionReferenceException forReference(String transactionReference) {
        return new DuplicateTransactionReferenceException("Transaction reference already in use: " + transactionReference);
    }
}
