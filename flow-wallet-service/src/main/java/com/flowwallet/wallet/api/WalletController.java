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
 * A wallet is addressed by its currency, not by an id.
 * <p>
 * The owner is already authenticated, and a user holds at most one wallet per currency, so the pair
 * identifies it exactly. An id in the path would be a second name for the same thing that the caller has no
 * truthful way to learn — the mistake that put a client-chosen wallet id into the payment request, removed
 * for the same reason. It also means ownership is the query rather than a step after it, so no handler can
 * forget to check it.
 * <p>
 * {@code @Validated} is load-bearing rather than decorative: without it, constraints on method parameters
 * are never evaluated, and the caps below would silently do nothing.
 */
@Validated
@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
public class WalletController {
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final WalletService wallets;

    /**
     * Every wallet the caller holds. An empty list is a correct answer, never a 404 — the caller exists,
     * they simply hold nothing yet.
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
