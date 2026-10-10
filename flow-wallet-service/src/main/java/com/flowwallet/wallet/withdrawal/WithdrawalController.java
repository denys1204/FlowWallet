package com.flowwallet.wallet.withdrawal;

import com.flowwallet.platform.security.CurrentUserId;
import com.flowwallet.wallet.transfer.TransferController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Withdrawing from a wallet to the caller's bank account.
 * <p>
 * A withdrawal answers 202, not 200: the wallet is debited now and Payment Service pays out later, and the
 * withdrawal's own address shows how far it got. {@code Location} is built from the receipt's currency and reference
 * only, never from the request's host or casing, so a replay sends the same bytes. {@code @CurrentUserId} stays the
 * first parameter: Spring MVC resolves arguments in declaration order, so a caller without a usable identity gets 401
 * before the rest of the request is looked at.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/wallets/{currency}/withdrawals")
public class WithdrawalController {
    private final WithdrawalService withdrawals;

    /**
     * The mapping declares that it produces JSON, so an {@code Accept} header that rules JSON out gets 406 before the
     * handler runs. Without it the wallet would be debited first and the 406 would come after.
     *
     * @param idempotencyKey the caller's retry token, which becomes the withdrawal's reference; required, never
     *                       generated server-side, and any UUID version, unlike the caller's own id.
     *                       See docs/adr/0005-client-supplied-idempotency-keys.md.
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WithdrawalResponse> withdraw(
            @CurrentUserId String userId,
            @PathVariable String currency,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = TransferController.ANY_UUID, message = "Idempotency-Key must be a UUID")
            String idempotencyKey,
            @Valid @RequestBody WithdrawalRequest request
    ) {
        WithdrawalResponse receipt = withdrawals.withdraw(userId, currency, idempotencyKey, request);
        return ResponseEntity.accepted().location(location(receipt)).body(receipt);
    }

    @GetMapping("/{reference}")
    public WithdrawalStatusResponse read(
            @CurrentUserId String userId,
            @PathVariable String currency,
            @PathVariable
            @Pattern(regexp = TransferController.ANY_UUID, message = "reference must be a UUID")
            String reference
    ) {
        return withdrawals.read(userId, currency, reference);
    }

    private static URI location(WithdrawalResponse receipt) {
        return UriComponentsBuilder.fromPath("/api/wallets/{currency}/withdrawals/{reference}")
                .buildAndExpand(receipt.currency(), receipt.reference())
                .toUri();
    }
}
