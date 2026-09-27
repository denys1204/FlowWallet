package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.constant.HttpHeaders;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * The wallet's one outbound call.
 * <p>
 * The caller's id is an explicit parameter. An interceptor reading request-scoped state would send an empty
 * header, with no error, once the call leaves the request thread. See docs/adr/0013-deposit-initiation.md.
 */
@HttpExchange
interface PaymentIntentClient {
    @PostExchange("/api/payments/intent")
    PaymentIntentResult createIntent(
            @RequestHeader(HttpHeaders.USER_ID) String userId,
            @RequestBody CreatePaymentIntentCommand command
    );
}
