package com.flowwallet.wallet.transfer;

import com.flowwallet.platform.security.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Moving money from the caller's wallet to another user's wallet in the same currency.
 * <p>
 * The first answer is 200, like its replay, because no URL addresses a transfer. {@code @CurrentUserId} stays the
 * first parameter: Spring MVC resolves arguments in declaration order, so a caller without a usable identity gets
 * 401 before the rest of the request is looked at. See docs/adr/0014-transfers-in-one-local-transaction.md.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/wallets/{currency}/transfers")
public class TransferController {
    /**
     * Any UUID: versions 1 to 8 of variants 0 to 2, in either case, never nil (its version digit is 0), in ASCII
     * only. {@code DepositController} checks its key with it too. It is a pattern because Hibernate Validator's
     * {@code @UUID} throws on a 36-character key with a fifth dash, which the platform handler answers with a 500
     * instead of a 400, and accepts non-ASCII digits. See docs/adr/0005-client-supplied-idempotency-keys.md.
     */
    public static final String ANY_UUID =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[0-9a-dA-D][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

    private final TransferService transfers;

    /**
     * The mapping declares that it produces JSON, so an {@code Accept} header that rules JSON out gets 406 before
     * the handler runs. Without it the money would move first and the 406 would come after.
     *
     * @param idempotencyKey the caller's retry token, required and stored as the reference of both legs.
     *                       See docs/adr/0005-client-supplied-idempotency-keys.md.
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public TransferResponse transfer(
            @CurrentUserId String userId,
            @PathVariable String currency,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = ANY_UUID, message = "Idempotency-Key must be a UUID")
            String idempotencyKey,
            @Valid @RequestBody TransferRequest request
    ) {
        return transfers.transfer(userId, currency, idempotencyKey, request);
    }
}
