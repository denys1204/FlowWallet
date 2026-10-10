package com.flowwallet.payment.payout;

import com.flowwallet.payment.dto.PayoutPreflightRequest;
import com.flowwallet.payment.dto.PayoutPreflightResponse;
import com.flowwallet.platform.security.CurrentUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payout questions asked before a withdrawal is debited. The gateway does not route here.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md and
 * docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/payouts")
public class PayoutController {
    private final PayoutPreflight preflight;

    @PostMapping("/preflight")
    public PayoutPreflightResponse preflight(
            @CurrentUserId String userId,
            @Valid @RequestBody PayoutPreflightRequest request
    ) {
        return preflight.check(userId, request);
    }
}
