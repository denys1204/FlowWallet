package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.security.CurrentUserId;
import com.flowwallet.wallet.dto.DepositRequest;
import com.flowwallet.wallet.transfer.TransferController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Starting a deposit into a wallet.
 * <p>
 * Answers 200: nothing is created on this side, and the client finishes the payment with the provider's SDK.
 * {@code @CurrentUserId} stays the first parameter: Spring MVC resolves arguments in declaration order, so a caller
 * without a usable identity gets 401 before the rest of the request is looked at.
 * See docs/adr/0013-deposit-initiation.md.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/wallets/{currency}/deposits")
public class DepositController {
    private final DepositService deposits;

    /**
     * The mapping declares that it produces JSON, so an {@code Accept} header that rules JSON out gets 406 before
     * the handler runs. Without it Payment Service would create the intent first and the 406 would come after.
     *
     * @param idempotencyKey the caller's retry token, which becomes the payment's reference; required, never
     *                       generated server-side, and any UUID version, unlike the caller's own id.
     *                       See docs/adr/0005-client-supplied-idempotency-keys.md.
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public DepositResponse start(
            @CurrentUserId String userId,
            @PathVariable String currency,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = TransferController.ANY_UUID, message = "Idempotency-Key must be a UUID")
            String idempotencyKey,
            @Valid @RequestBody DepositRequest request
    ) {
        return deposits.start(userId, currency, idempotencyKey, request);
    }
}
