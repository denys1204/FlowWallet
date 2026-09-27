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
  `provider_metadata JSONB` and timestamps. `provider_transaction_id` also has a separate non-unique index.
- `outbox_events` holds `id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload TEXT`, `status`,
  `retry_count`, `error_message`, `next_attempt_at` (backoff), `processing_started_at` (stuck-row
  detection), `created_at` and `processed_at`. It is indexed on (`status`, `created_at`) for the poller.

`wallet_db` is managed by Liquibase in `flow-wallet-service`:

- `wallets` holds `id`, `user_id VARCHAR(64)`, `balance NUMERIC(19,4)` (default 0, and a CHECK refuses a
  value below zero), `currency VARCHAR(3)` (a CHECK forces upper case), `version` and timestamps. `version` is
  an optimistic-lock backstop, since credits and transfers take a `PESSIMISTIC_WRITE` row lock. The table is
  unique on (`user_id`, `currency`): one wallet per user per currency.
- `balance_history` is the append-only ledger: `id`, `wallet_id` (indexed), `transaction_reference`,
  `event_id` (nullable), `type`, `counterparty_user_id VARCHAR(64)` (nullable), `amount`, `balance_before`,
  `balance_after` and `created_at`. `type` is `DEPOSIT`, `TRANSFER_IN` or `TRANSFER_OUT`. `WITHDRAWAL` is
  declared for withdrawals, and nothing writes it yet. The table is unique on (`transaction_reference`,
  `type`), so both legs of a transfer share one reference while a payment can still be credited only once
  and a key can start only one transfer. The amount is always positive and `type` gives the direction, so a
  CHECK refuses an amount of zero or less. That includes a positive value too small for four decimal places,
  such as `0.00001`, which Postgres would otherwise round to `0.0000` and store. `counterparty_user_id` is
  the user on the other side of a transfer: the recipient on `TRANSFER_OUT`, the sender on `TRANSFER_IN`. A
  second CHECK makes it present on the two transfer types and absent on every other, so a transfer leg
  without a counterparty can't be stored. `event_id` is NULL on both transfer legs, since a transfer never
  passes through Kafka.
- `processed_events` holds one row per event the consumer settles (credited, failure recorded or refused).
  Unreadable records and records that still fail after retries go to the dead-letter topic and leave no row.
  Its columns are `id`, `event_id` (unique), `event_type`,
  `transaction_reference`, `amount`, `outcome` (`CREDITED`/`FAILURE_RECORDED`/`REJECTED`),
  `rejection_reason`, `payload TEXT` (kept for `FAILURE_RECORDED` and `REJECTED`, NULL for `CREDITED`) and
  `processed_at`. It is indexed on (`outcome`, `processed_at`).

Every table's `id` comes from a `<table>_seq` sequence with an increment of 50, matching the entities'
`allocationSize = 50`.

The rules behind `wallets` and `balance_history` (a balance that never goes negative, positive amounts, the
(`transaction_reference`, `type`) key and the counterparty) are in
[ADR 0012](adr/0012-balances-and-append-only-ledger.md).

`docker/postgres/init-databases.sql` creates both databases when the Postgres volume is first initialised.
