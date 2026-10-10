package com.flowwallet.wallet.bankaccount;

import com.flowwallet.platform.constant.HttpHeaders;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PutExchange;

/**
 * Payment Service's bank account routes, which the gateway does not expose. The caller's id is an explicit
 * parameter, as on {@code PaymentIntentClient}.
 * <p>
 * The interface stays package-private, like its records: the JDK proxy of a public interface is defined in a module
 * of its own, which cannot reach package-private types, so every call would fail.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
@HttpExchange("/api/payments/payout-accounts")
interface PayoutAccountClient {
    @PutExchange
    PayoutAccountResult set(
            @RequestHeader(HttpHeaders.USER_ID) String userId,
            @RequestBody PayoutAccountCommand command
    );

    @GetExchange
    PayoutAccountResult get(@RequestHeader(HttpHeaders.USER_ID) String userId);
}
