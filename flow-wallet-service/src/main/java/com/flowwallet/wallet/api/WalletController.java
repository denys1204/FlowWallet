package com.flowwallet.wallet.api;

import com.flowwallet.platform.security.CurrentUserId;
import com.flowwallet.wallet.dto.CreateWalletRequest;
import com.flowwallet.wallet.dto.HistoryPage;
import com.flowwallet.wallet.dto.WalletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A wallet is addressed by the caller's id and the currency in the path, never by a wallet id.
 * See docs/adr/0004-wallet-addressed-by-owner-and-currency.md.
 * <p>
 * {@code @Validated} decides how a parameter violation is reported, not whether it is caught. Without it,
 * Spring MVC raises {@code HandlerMethodValidationException} and the problem comes back without its
 * {@code errors} list. See docs/adr/0016-error-model-and-status-codes.md.
 */
@Validated
@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
public class WalletController {
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final WalletService wallets;

    /**
     * Every wallet the caller holds, ordered by currency; an empty list, never a 404, when there are none.
     */
    @GetMapping
    public List<WalletResponse> list(@CurrentUserId String userId) {
        return wallets.listFor(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WalletResponse open(@Valid @RequestBody CreateWalletRequest request, @CurrentUserId String userId) {
        return wallets.open(userId, request.currency());
    }

    @GetMapping("/{currency}")
    public WalletResponse read(@PathVariable String currency, @CurrentUserId String userId) {
        return wallets.read(userId, currency);
    }

    /**
     * Movements, newest first, paged by cursor. {@code before} is the id of the oldest movement already
     * seen; omit it for the first page.
     */
    @GetMapping("/{currency}/history")
    public HistoryPage history(
            @PathVariable String currency,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = "limit must be at least 1")
            @Max(value = 100, message = "limit must not exceed 100") int limit,
            @CurrentUserId String userId
    ) {
        return wallets.history(userId, currency, before, limit);
    }
}
