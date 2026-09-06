package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.security.CurrentUserId;
import com.flowwallet.wallet.dto.DepositRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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

    /**
     * Strict on purpose. {@code UUID.fromString} is not a validator: it accepts {@code 1-1-1-1-1} and turns
     * it into a real UUID, and {@code 0-0-0-0-0} into the nil one. Two callers sending different malformed
     * strings could normalise onto the same value, which is the collision the UUID rule exists to prevent;
     * and validating with it while storing the original would make two spellings of one key into two
     * references, so a retry would start a second payment.
     */
    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final DepositService deposits;

    /**
     * @param idempotencyKey the caller's retry token, which becomes the payment's reference. Required: an
     *                       optional key would mean whoever forgets it silently loses idempotency on the one
     *                       unsafe endpoint here, and a lost response would charge twice. Never generated
     *                       server-side for the same reason — a fresh key on every retry is no key at all.
     */
    @PostMapping
    public DepositResponse start(
            @PathVariable String currency,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = UUID_PATTERN, message = "Idempotency-Key must be a UUID") String idempotencyKey,
            @Valid @RequestBody DepositRequest request,
            @CurrentUserId String userId
    ) {
        return deposits.start(userId, currency, idempotencyKey, request);
    }
}
