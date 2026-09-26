package com.flowwallet.payment.transaction;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

public class TransactionNotFoundException extends ApiException {
    public TransactionNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
