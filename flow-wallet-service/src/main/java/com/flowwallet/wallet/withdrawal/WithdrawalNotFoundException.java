package com.flowwallet.wallet.withdrawal;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * No withdrawal of the caller's wallet in the currency has the reference. Maps to HTTP 404. An unknown reference,
 * another wallet's and another user's all get this one answer, so it describes nobody's withdrawal.
 */
public class WithdrawalNotFoundException extends ApiException {
    public WithdrawalNotFoundException(String currency) {
        super(HttpStatus.NOT_FOUND, "No such withdrawal in the " + currency + " wallet");
    }
}
