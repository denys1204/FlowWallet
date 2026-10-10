package com.flowwallet.wallet.withdrawal;

/**
 * The wallet's reading of the preflight's bank account: {@code READY} when Payment Service can pay out to it now.
 */
enum BankAccountState {
    READY,
    NOT_SET
}
