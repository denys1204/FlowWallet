package com.flowwallet.payment.transaction;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a transaction reference is already in use — won by a concurrent request, owned by a
 * different user, or reused for a payment on different terms. Maps to HTTP 409 Conflict.
 * <p>
 * The three cases are not distinguishable by status code, and deliberately so: the responses carry no
 * problem {@code type} beyond {@code about:blank}, so inventing a taxonomy here would promise callers a
 * precision the representation does not deliver. The detail says which it was.
 */
public class DuplicateTransactionReferenceException extends ApiException {
    private DuplicateTransactionReferenceException(String message) {
        super(HttpStatus.CONFLICT, message);
    }

    public static DuplicateTransactionReferenceException forReference(String transactionReference) {
        return new DuplicateTransactionReferenceException("Transaction reference already in use: " + transactionReference);
    }

    /**
     * The reference names a payment that already completed. Returning its intent again would hand back a
     * credential that has been spent, with a 200 that says everything is fine — the client would then fail
     * at the provider, learning from a third party what this service already knew.
     */
    public static DuplicateTransactionReferenceException forSettledReference(String transactionReference) {
        return new DuplicateTransactionReferenceException(
                "Transaction reference %s was already paid".formatted(transactionReference)
        );
    }

    /**
     * The reference exists and names a payment on different terms. 409 rather than 422 because the request
     * is not itself invalid — the same body under an unused reference would be accepted.
     *
     * @param differingFields which terms disagree; never their stored values, since this message is
     *                        rendered to the caller
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
