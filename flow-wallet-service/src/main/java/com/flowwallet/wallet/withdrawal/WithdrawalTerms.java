package com.flowwallet.wallet.withdrawal;

import java.math.BigDecimal;

/**
 * A withdrawal's terms with every value already normalised: the user id and the reference lower-cased, the currency
 * an upper-case ISO code, and the amount on the wallet's grid at the ledger's scale. The same amount reaches the
 * preflight, the debit, the rows and the command.
 */
record WithdrawalTerms(String userId, String currency, String reference, BigDecimal amount) {
}
