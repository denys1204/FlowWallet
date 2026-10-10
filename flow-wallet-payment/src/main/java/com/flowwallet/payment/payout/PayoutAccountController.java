package com.flowwallet.payment.payout;

import com.flowwallet.payment.dto.PayoutAccountRequest;
import com.flowwallet.payment.dto.PayoutAccountResponse;
import com.flowwallet.platform.security.CurrentUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's bank account, for Wallet Service's relay. The gateway does not route here, so only a caller on Payment
 * Service's own network reaches it. See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/payout-accounts")
public class PayoutAccountController {
    private final PayoutAccountService service;

    @PutMapping
    public PayoutAccountResponse setBankAccount(
            @CurrentUserId String userId,
            @Valid @RequestBody PayoutAccountRequest request
    ) {
        return service.setBankAccount(userId, request.token());
    }

    @GetMapping
    public PayoutAccountResponse bankAccount(@CurrentUserId String userId) {
        return service.bankAccount(userId);
    }
}
