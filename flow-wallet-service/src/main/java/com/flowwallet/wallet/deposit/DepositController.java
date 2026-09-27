package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.security.CurrentUserId;
import com.flowwallet.wallet.dto.DepositRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.constraints.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Starting a deposit into a wallet.
 * <p>
 * Answers 200: nothing is created on this side, and the client finishes the payment with the provider's SDK.
 * See docs/adr/0013-deposit-initiation.md.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/wallets/{currency}/deposits")
public class DepositController {
    private final DepositService deposits;

    /**
     * Any UUID version is accepted, unlike the caller's own id. The versions are listed because the annotation's
     * default, 1 to 5, refuses the version-7 keys many client libraries generate.
     * See docs/adr/0005-client-supplied-idempotency-keys.md.
     *
     * @param idempotencyKey the caller's retry token, which becomes the payment's reference; required, and never
     *                       generated server-side
     */
    @PostMapping
    public DepositResponse start(
            @PathVariable String currency,
            @RequestHeader("Idempotency-Key")
            @UUID(
                    allowNil = false,
                    letterCase = UUID.LetterCase.INSENSITIVE,
                    version = {1, 2, 3, 4, 5, 6, 7, 8},
                    message = "Idempotency-Key must be a UUID"
            ) String idempotencyKey,
            @Valid @RequestBody DepositRequest request,
            @CurrentUserId String userId
    ) {
        return deposits.start(userId, currency, idempotencyKey, request);
    }
}
