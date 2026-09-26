package com.flowwallet.wallet.transfer;

import com.flowwallet.platform.security.CurrentUserId;
import com.flowwallet.wallet.dto.TransferRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Moving money from the caller's wallet to another user's wallet in the same currency.
 * <p>
 * Returns 200 rather than 201. No URL addresses a transfer, so there is nothing for a {@code Location} to
 * point at, and a replay of the same Idempotency-Key must match the first answer in status as well as in
 * body. A 201 for the first answer and a 200 for its replay would tell a client two different things about
 * one transfer.
 * <p>
 * {@code @CurrentUserId} is the first parameter on purpose. Spring MVC resolves arguments in declaration
 * order, so a caller without a usable identity is refused with 401 before its other headers or its body are
 * looked at, and is never told what else was wrong with a request it had no standing to make. The one
 * exception is an {@code Accept} header that rules out JSON, which is refused with 406 while the handler is
 * chosen, before any argument is resolved.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/wallets/{currency}/transfers")
public class TransferController {
    /**
     * Any UUID: versions 1 to 8 of variants 0 to 2, in either case, never nil (its version digit is 0). These
     * are the keys the deposit's {@code @UUID} is configured to accept, written as a pattern because that
     * validator counts dashes without bounding them. A 36-character key with a fifth dash makes it throw
     * inside validation, and the platform handler answers that exception with a 500 instead of a 400.
     */
    private static final String ANY_UUID =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[0-9a-dA-D][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

    private final TransferService transfers;

    /**
     * The key rule is the deposit's, and {@link com.flowwallet.wallet.deposit.DepositController#start} gives
     * the reasoning: any UUID version, because the key must be unique rather than unguessable. One difference
     * is worth knowing: the recipient sees a transfer's key in its history, so a key derived from something
     * guessable lets the recipient predict, and take first, the sender's next one. Random keys avoid that.
     * <p>
     * The mapping declares that it produces JSON. Without that, a request whose {@code Accept} header rules
     * JSON out would still reach the handler, the money would move, and only then would writing the receipt
     * fail with a 406, which a client that treats a 4xx as final would read as a transfer that never happened.
     *
     * @param idempotencyKey the caller's retry token, stored as the reference of both legs. Required, because
     *                       without it a lost response followed by a retry would move the money twice.
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
