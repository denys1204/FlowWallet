package com.flowwallet.wallet.bankaccount;

/**
 * What the wallet sends Payment Service: a copy of its {@code PayoutAccountRequest}, kept in step by hand.
 * See docs/adr/0002-module-boundaries.md.
 */
record PayoutAccountCommand(String token, Boolean tosAccepted) {
}
