package com.flowwallet.wallet.bankaccount;

import com.flowwallet.platform.security.CurrentUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's bank account for withdrawals, relayed to Payment Service. {@code @CurrentUserId} stays the first
 * parameter, so a caller without a usable identity gets 401 before the body is looked at.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/wallets/bank-account")
public class BankAccountController {
    private final BankAccountService bankAccounts;

    /**
     * Declares that it produces JSON, so an {@code Accept} header that rules JSON out gets 406 before Stripe is
     * called.
     */
    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public BankAccountResponse set(@CurrentUserId String userId, @Valid @RequestBody BankAccountRequest request) {
        return bankAccounts.set(userId, request);
    }

    @GetMapping
    public BankAccountResponse get(@CurrentUserId String userId) {
        return bankAccounts.get(userId);
    }
}
