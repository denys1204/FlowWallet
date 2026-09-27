package com.flowwallet.wallet.dto;

import java.util.List;

/**
 * A page of movements, newest first, paged by cursor: a credit landing between two page reads would shift every
 * offset. See docs/adr/0021-per-wallet-ledger-entry-numbers.md.
 *
 * @param nextBefore the entry number of the page's oldest movement, passed as {@code before} for the next page;
 *                   {@code null} when there is nothing older
 */
public record HistoryPage(
        List<BalanceHistoryResponse> items,
        Long nextBefore
) {}
