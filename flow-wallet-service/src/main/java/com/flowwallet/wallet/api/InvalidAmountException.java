package com.flowwallet.wallet.api;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * An amount the wallet cannot move as written: finer than its currency allows, or too large for a balance.
 * Maps to HTTP 400.
 * <p>
 * Built only through the factories, so each refusal has one wording and the reason is in the name of the call
 * that raised it.
 */
public class InvalidAmountException extends ApiException {
    private InvalidAmountException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }

    /**
     * @param acceptedScale most decimal places an amount in this currency may carry
     */
    public static InvalidAmountException tooPrecise(String currency, int acceptedScale) {
        String detail = acceptedScale == 0
                ? currency + " amounts must be whole units"
                : currency + " amounts carry at most " + acceptedScale + " decimal places";
        return new InvalidAmountException(detail);
    }

    public static InvalidAmountException tooLarge() {
        return new InvalidAmountException("Amount does not fit a wallet balance");
    }
}
