package com.flowwallet.wallet.dto;

import java.util.List;

/**
 * A page of movements, newest first.
 * <p>
 * Paged by cursor rather than by offset. The ledger is append-only and served newest first, so a credit
 * landing between two page reads shifts every offset and the client sees a movement twice or not at all.
 * That is a correctness problem, not a performance one, and no page size makes it go away.
 *
 * @param items      the movements, newest first
 * @param nextBefore cursor for the next page, or {@code null} when there is nothing older
 */
public record HistoryPage(
        List<BalanceHistoryResponse> items,
        Long nextBefore
) {}
