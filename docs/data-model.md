# FlowWallet data model

This document covers the FlowWallet database schema: the payment and wallet databases, their tables, and the
constraints that keep balances consistent. See [ARCHITECTURE.md](../ARCHITECTURE.md) for how the system fits
together, [api.md](api.md) for the endpoints built on these tables, and [events.md](events.md) for the Kafka
events that connect these two databases.

## Data model

`payment_db` is managed by Liquibase in `flow-wallet-payment`:

- `payment_transactions` holds `id`, `transaction_reference` (unique idempotency key), `provider_name`,
  `provider_transaction_id` (unique), `user_id`, `amount NUMERIC(19,4)`, `currency`,
  `status` (`PENDING`/`SUCCESS`/`FAILED`), `provider_event_id` (unique), `version` (optimistic lock),
  `provider_metadata JSONB`, `last_reconciled_at` (when the reconciler last asked Stripe about the payment) and
  timestamps. The index behind the unique constraint on `provider_transaction_id` serves the webhook's lookup by
  provider id, and a partial index on `created_at` for initiated payments that have not succeeded serves the
  reconciler's scan.
- `outbox_events` holds `id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload TEXT`, `status`,
  `retry_count`, `error_message` (the cause chain of the last failed send), `next_attempt_at` (backoff),
  `processing_started_at` (the claim time, set only while the row is `PROCESSING`; the reaper's age check and
  the sender's ownership check), `created_at` and `processed_at`. It is indexed on (`status`, `created_at`) for
  the poller.
- `payout_accounts` holds one row per user: `id`, `user_id` (unique), `account_key` (server-generated and unique; it
  keys the creation of the Stripe account), `stripe_account_id` (unique, NULL until Stripe created it),
  `tos_accepted_at` (when the user accepted Stripe's terms, sent verbatim on every retry), `external_account_id`,
  `bank_last4`, `bank_currency` (upper case, as payouts carry it), `last_token` (the last bank token attached),
  `bank_state` (`READY`/`NOT_SET`, and a CHECK requires both Stripe ids for `READY`), `version` and timestamps. No bank
  details are stored.
- `payouts` holds one row per withdrawal command: `id`, `transaction_reference` (unique as `payouts_reference_key`),
  `user_id`, `amount`, `currency`, `stripe_account_id`, `stripe_transfer_id` (unique), `stripe_payout_id` (unique),
  `stripe_reversal_id`, `status` (`ACCEPTED`/`TRANSFERRED`/`PAYOUT_CREATED`/`PAID`/`REVERSING`/`RETURNED`/
  `NEEDS_ATTENTION`), `reason_code`, `failure_code`, and the executor's bookkeeping: `key_gen`, each step's
  `*_attempted_at` (the time of its first claim), `transferred_at`, `paid_at`, `attempts`, `next_attempt_at` (NOT NULL),
  `claimed_until` (the lease, also its ownership token), `attention_from`, `attention_cause` and `attention_detail` (why
  the payout waits for an operator and what the executor saw, cut to 255 characters; set only in `NEEDS_ATTENTION`, with
  the cause one of `UNKNOWN_LIMIT`/`DEFECT`/`MISMATCH`/`MULTIPLE`/`LIVE_PAYOUT`/`REVERSAL_REFUSED`) and
  `last_reconciled_at`, plus `version` and timestamps. While a payout waits, `updated_at` is the time it was handed
  over, because nothing writes a waiting row until an operator acts, and the `payouts` actuator endpoint counts its
  quiet period from it. CHECKs keep the amount positive, the status known, a reason on a payout on its way back or
  returned, the Stripe ids each status needs, `attention_from` set exactly in `NEEDS_ATTENTION`, the cause one the code
  knows (`payouts_attention_cause_known`) and the cause and detail empty outside `NEEDS_ATTENTION`
  (`payouts_attention_cause_only_when_needed`). Neither cause CHECK requires a cause inside `NEEDS_ATTENTION`, because a
  row handed over before the columns existed has none, and the code sets one on every hand-over. Partial indexes serve
  the executor's scan of due rows, the reconciler's scan of created and paid ones, and the operator's list and count of
  waiting ones (`idx_payouts_attention`, on `updated_at` and `id`)
  ([ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md),
  [ADR 0037](adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md)).

`wallet_db` is managed by Liquibase in `flow-wallet-service`:

- `wallets` holds `id`, `user_id VARCHAR(64)`, `balance NUMERIC(19,4)` (default 0, and a CHECK refuses a
  value below zero), `currency VARCHAR(3)` (a CHECK forces upper case), `last_entry_no BIGINT` (the entry
  number of the wallet's newest ledger row, 0 before the first), `version` and timestamps. `version` is an
  optimistic-lock backstop, since credits, transfers and withdrawals take a `PESSIMISTIC_WRITE` row lock. The table
  is unique on (`user_id`, `currency`): one wallet per user per currency.
- `balance_history` is the append-only ledger: `id`, `wallet_id`, `entry_no BIGINT`, `transaction_reference`, `event_id`
  (nullable), `type`, `counterparty_user_id VARCHAR(64)` (nullable), `amount`, `balance_before`, `balance_after` and
  `created_at`. `type` is `DEPOSIT`, `TRANSFER_IN`, `TRANSFER_OUT`, `WITHDRAWAL` (a withdrawal's debit, written with its
  `withdrawals` row) or `WITHDRAWAL_RETURNED` (its money coming back, written when a `PayoutReturnedEvent` arrives). The
  table is unique on (`transaction_reference`, `type`), so both legs of a transfer share one reference, and a
  withdrawal's debit and its return share the withdrawal's, while a payment can still be credited only once and a key
  can start only one transfer or one withdrawal. The amount is
  always positive and `type` gives the direction, so a CHECK refuses an amount of zero or less. That includes a positive
  value too small for four decimal places, such as `0.00001`, which Postgres would otherwise round to `0.0000` and
  store. `counterparty_user_id` is the user on the other side of a transfer: the recipient on `TRANSFER_OUT`, the sender
  on `TRANSFER_IN`. A second CHECK makes it present on the two transfer types and absent on every other, so a transfer
  leg without a counterparty can't be stored. `event_id` is NULL on both transfer legs, since a transfer never passes
  through Kafka, and on a withdrawal's debit, which no `payment.events` record causes. `entry_no` numbers each wallet's
  movements 1, 2, 3 in commit order: every balance change advances `wallets.last_entry_no` under the row lock and gives
  its ledger row the new value. The table is unique on (`wallet_id`, `entry_no`)
  (`balance_history_wallet_entry_no_key`), and that index also serves every read by wallet, including the history, which
  is ordered and paged by `entry_no`.
- `processed_events` holds one row per event the consumer settles (credited, credited back, failure or status recorded,
  or refused). Unreadable records and records that still fail after retries go to the dead-letter topic and leave no
  row. Its columns are `id`, `event_id` (unique), `event_type`, `transaction_reference`, `amount`, `outcome`
  (`CREDITED`/`FAILURE_RECORDED`/`REJECTED`, and `RETURN_CREDITED`/`STATUS_RECORDED` for payout events),
  `rejection_reason`, `payload TEXT` (kept for `FAILURE_RECORDED`, `STATUS_RECORDED` and `REJECTED`, NULL for `CREDITED`
  and `RETURN_CREDITED`) and `processed_at`. It is indexed on (`outcome`, `processed_at`). `amount` is NULL when the
  event carried none or carried one that `NUMERIC(19,4)` cannot hold exactly, such as `0.00001`, which Postgres would
  round, or a 16-digit amount, which it would refuse; the payload keeps the figure as sent
  ([ADR 0019](adr/0019-payment-event-amounts-on-the-grid.md)).
- `withdrawals` holds one row per withdrawal request: `id`, `wallet_id` (a plain id, like the ledger's),
  `transaction_reference` (unique as `withdrawals_reference_key`), `amount`, `status`
  (`PENDING`/`PAID`/`RETURN_PENDING`/`RETURNED`/`REFUSED`), `refusal_reason` (set exactly when the status is `REFUSED`),
  `return_reason` (the reason code of the payout event that last raised the status, or `OTHER`; set exactly on a
  withdrawal on its way back or returned), `version` and timestamps. CHECKs hold the amount positive, the status known
  and each reason to its statuses. The money moves in `balance_history`, not here
  ([ADR 0033](adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md)).
- `outbox_events` has the same columns and index as Payment Service's, because the shared `flow-wallet-outbox`
  module maps one entity for both services. It holds the withdrawal commands Wallet Service sends
  ([ADR 0034](adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md)).

Every table's `id` comes from a `<table>_seq` sequence with an increment of 50, matching the entities'
`allocationSize = 50`. Each instance hands out its own block of 50, so ids do not follow commit order, and
nothing orders by them ([ADR 0021](adr/0021-per-wallet-ledger-entry-numbers.md)).

The rules behind `wallets` and `balance_history` (a balance that never goes negative, positive amounts, the
(`transaction_reference`, `type`) key and the counterparty) are in
[ADR 0012](adr/0012-balances-and-append-only-ledger.md); the entry numbers are in
[ADR 0021](adr/0021-per-wallet-ledger-entry-numbers.md).

`docker/postgres/init-databases.sql` creates both databases when the Postgres volume is first initialised.
