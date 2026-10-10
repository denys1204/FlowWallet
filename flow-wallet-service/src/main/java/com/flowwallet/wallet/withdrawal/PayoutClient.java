package com.flowwallet.wallet.withdrawal;

import com.flowwallet.platform.constant.HttpHeaders;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Payment Service's withdrawal preflight, which the gateway does not expose. The caller's id is an explicit
 * parameter, as on {@code PaymentIntentClient}.
 * <p>
 * The interface stays package-private, like its records: the JDK proxy of a public interface is defined in a module
 * of its own, which cannot reach package-private types, so every call would fail.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
@HttpExchange("/api/payments/payouts")
interface PayoutClient {
    @PostExchange("/preflight")
    PayoutPreflightResult preflight(
            @RequestHeader(HttpHeaders.USER_ID) String userId,
            @RequestBody PayoutPreflightCommand command
    );
}
