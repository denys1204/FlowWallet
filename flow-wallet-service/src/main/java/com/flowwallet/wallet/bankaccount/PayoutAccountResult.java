package com.flowwallet.wallet.bankaccount;

import org.jspecify.annotations.Nullable;

/**
 * Payment Service's answer: a copy of its {@code PayoutAccountResponse}, kept in step by hand.
 * See docs/adr/0002-module-boundaries.md.
 */
record PayoutAccountResult(String state, @Nullable String last4, @Nullable String currency) {
}
