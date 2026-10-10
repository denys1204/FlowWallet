package com.flowwallet.wallet.withdrawal;

import java.math.BigDecimal;

/**
 * A hand copy of Payment Service's {@code PayoutPreflightRequest}, which {@code PayoutPreflightWireFormatTest} keeps
 * in step (ADR 0002). The amount stays a JSON number at the ledger's scale, such as 75.0000; stripping its zeros would
 * print 100 as 1E+2.
 */
record PayoutPreflightCommand(String currency, BigDecimal amount) {
}
