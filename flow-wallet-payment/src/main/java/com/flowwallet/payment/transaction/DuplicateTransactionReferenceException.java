package com.flowwallet.payment.transaction;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * One 409 for a reference that is already taken by another user's payment, by a payment that succeeded or by one on
 * other terms, whether it was there first or won a concurrent reservation. The detail names the case.
 * See docs/adr/0005-client-supplied-idempotency-keys.md.
 */
public class DuplicateTransactionReferenceException extends ApiException {
    private DuplicateTransactionReferenceException(String message) {
        super(HttpStatus.CONFLICT, message);
    }

    public static DuplicateTransactionReferenceException forReference(String transactionReference) {
        return new DuplicateTransactionReferenceException(
                "Transaction reference already in use: " + transactionReference
        );
    }

    /**
     * The payment already succeeded, so replaying its intent would hand back a spent client secret.
     */
    public static DuplicateTransactionReferenceException forSettledReference(String transactionReference) {
        return new DuplicateTransactionReferenceException(
                "Transaction reference %s was already paid".formatted(transactionReference)
        );
    }

    /**
     * @param differingFields which terms differ, never their stored values, because the message reaches the caller
     */
    public static DuplicateTransactionReferenceException forConflictingPayload(
            String transactionReference,
            String differingFields
    ) {
        return new DuplicateTransactionReferenceException(
                "Transaction reference %s was already used for a payment with a different %s"
                        .formatted(transactionReference, differingFields)
        );
    }
}
