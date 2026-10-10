package com.flowwallet.payment.provider.stripe;

import com.stripe.model.Account;
import com.stripe.model.Balance;
import com.stripe.model.BankAccount;
import com.stripe.model.Payout;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedStripeEventTest {
    private static final Instant CREATED = Instant.parse("2026-10-10T08:00:00Z");

    @Test
    void theObjectIdOfAPayoutEventIsThePayoutId() {
        // Guards the Connect handler reading a payout id from the wrong place: it settles the payout the event's
        // object names, on the account the event's envelope names.
        Payout payout = new Payout();
        payout.setId("po_1");

        ParsedStripeEvent event = new ParsedStripeEvent("evt_1", "payout.paid", CREATED, "acct_1", payout);

        assertThat(event.objectId()).isEqualTo("po_1");
        assertThat(event.account()).isEqualTo("acct_1");
    }

    @Test
    void anAccountAndABankAccountAreIdentifiedByTheirOwnIds() {
        // Guards the id lookup covering only payouts: the other two objects the Connect endpoint receives carry ids
        // too, and the envelope's account is the connected account whichever object changed.
        Account account = new Account();
        account.setId("acct_1");
        BankAccount bankAccount = new BankAccount();
        bankAccount.setId("ba_1");

        ParsedStripeEvent accountEvent = new ParsedStripeEvent("evt_1", "account.updated", CREATED, "acct_1", account);
        ParsedStripeEvent bankEvent =
                new ParsedStripeEvent("evt_2", "account.external_account.updated", CREATED, "acct_1", bankAccount);

        assertThat(accountEvent.objectId()).isEqualTo("acct_1");
        assertThat(bankEvent.objectId()).isEqualTo("ba_1");
        assertThat(bankEvent.account()).isEqualTo("acct_1");
    }

    @Test
    void anObjectWithoutAnIdHasNoObjectId() {
        // Guards a ClassCastException or an empty string for an object that carries no id: the balance, for one,
        // has none, and the handler treats null as "nothing to act on".
        ParsedStripeEvent event = new ParsedStripeEvent("evt_1", "balance.available", CREATED, null, new Balance());

        assertThat(event.objectId()).isNull();
    }

    @Test
    void anObjectWhoseIdIsUnsetHasNoObjectId() {
        // Guards an event whose object lacks an id being matched to a payout under a made-up one.
        ParsedStripeEvent event = new ParsedStripeEvent("evt_1", "payout.paid", CREATED, "acct_1", new Payout());

        assertThat(event.objectId()).isNull();
    }
}
