package com.flowwallet.wallet.dto;

import java.util.List;

/**
 * A page of movements, newest first, paged by cursor: a credit landing between two page reads would shift every
 * offset. See docs/adr/0012-balances-and-append-only-ledger.md.
 *
 * @param nextBefore cursor for the next page, or {@code null} when there is nothing older
 */
public record HistoryPage(
        List<BalanceHistoryResponse> items,
        Long nextBefore
) {}
