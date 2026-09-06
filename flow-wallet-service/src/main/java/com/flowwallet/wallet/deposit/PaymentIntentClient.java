package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.constant.HttpHeaders;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * The wallet's one outbound call. Payment Service knows nothing about wallets and is not asked to — it takes
 * an instruction and answers.
 * <p>
 * The caller's identity travels as an explicit parameter rather than through an interceptor over a
 * request-scoped holder. An interceptor would send an empty header the moment this call moves off the
 * request thread, and it would do so silently.
 */
@HttpExchange
interface PaymentIntentClient {

    @PostExchange("/api/payments/intent")
    PaymentIntentResult createIntent(
            @RequestHeader(HttpHeaders.USER_ID) String userId,
            @RequestBody CreatePaymentIntentCommand command
    );
}
