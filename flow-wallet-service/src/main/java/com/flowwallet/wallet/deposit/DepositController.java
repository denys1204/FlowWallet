package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.security.CurrentUserId;
import com.flowwallet.wallet.dto.DepositRequest;
import jakarta.validation.Valid;
import org.hibernate.validator.constraints.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Starting a deposit into a wallet.
 * <p>
 * Returns 200 rather than 201 or 202. Nothing is created on this side, so there is no resource for a
 * {@code Location} to point at; and 202 would promise that the server finishes the work on its own, when in
 * fact the caller finishes it with the provider's SDK — a conventionally-written client would poll for a
 * balance that never moves.
 */
@Validated
@RestController
@RequestMapping("/api/wallets/{currency}/deposits")
@RequiredArgsConstructor
public class DepositController {

    private final DepositService deposits;

    /**
     * Any UUID version is accepted here, unlike the caller's own id. This key needs only to be unique, not
     * unguessable — a client deriving a stable key from an order number with a version-5 UUID is doing
     * something sensible, and refusing it would buy nothing.
     *
     * @param idempotencyKey the caller's retry token, which becomes the payment's reference. Required: an
     *                       optional key would mean whoever forgets it silently loses idempotency on the one
     *                       unsafe endpoint here, and a lost response would charge twice. Never generated
     *                       server-side for the same reason — a fresh key on every retry is no key at all.
     */
    @PostMapping
    public DepositResponse start(
            @PathVariable String currency,
            @RequestHeader("Idempotency-Key")
            @UUID(allowNil = false, allowEmpty = false, letterCase = UUID.LetterCase.INSENSITIVE,
                    message = "Idempotency-Key must be a UUID") String idempotencyKey,
            @Valid @RequestBody DepositRequest request,
            @CurrentUserId String userId
    ) {
        return deposits.start(userId, currency, idempotencyKey, request);
    }
}
