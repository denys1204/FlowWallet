package com.flowwallet.wallet.withdrawal;

import org.jspecify.annotations.Nullable;

/**
 * A hand copy of Payment Service's {@code PayoutPreflightResponse}. The bank account stays a string, so a value the
 * wallet does not know is read as an unreadable answer and never as {@code READY} or {@code NOT_SET}.
 *
 * @param reserveSufficient null while no bank account is ready, because Payment Service does not read the balance then
 */
record PayoutPreflightResult(@Nullable String bankAccount, @Nullable Boolean reserveSufficient) {
}
