# 0027. User ids stay out of logs and provider metadata

- Status: Accepted, supersedes the logging detail of [0014](0014-transfers-in-one-local-transaction.md), extends
  [0003](0003-caller-identity-and-trust-boundary.md)
- Date: 2026-09-27

## Context

`X-User-Id` is the only credential a caller has ([0003](0003-caller-identity-and-trust-boundary.md)): the header
is unauthenticated, and whoever writes it becomes that user. Several log statements and one piece of provider
metadata carried it in clear text anyway. `PaymentService.initiatePayment` logged it at INFO on every call.
`WalletService.open` logged it at INFO on a conflict. `TransferHandler.execute` logged it at INFO on every
completed transfer and at WARN when the recipient held no wallet. `StripeRequestMapper.toPaymentIntentParams`
put it into the Stripe PaymentIntent's metadata. Anyone who can read application logs, a log aggregator, or the
Stripe dashboard could read a user's id there and, because the id is the only credential, act as that user
through a transfer.

## Decision

No log statement or exception message names a user id, and Stripe never receives one. A log line uses whatever
already identifies the row without it:

- `PaymentService.initiatePayment` logs the `transactionReference`, which the caller supplied and already knows.
- `WalletService.open`'s conflict logs nothing: the violation aborted the transaction before a wallet existed, so
  there is no wallet id to log by, and `GlobalExceptionHandler` already logs the currency from the exception's
  own message.
- `TransferHandler.execute` logs the sending wallet's own id (`Wallet.getId()`), which no URL accepts
  ([0004](0004-wallet-addressed-by-owner-and-currency.md)) and by itself lets nobody act as anyone. Its
  missing-recipient warning drops the recipient's id entirely, because no recipient wallet exists to hold one.
- `StripeRequestMapper.toPaymentIntentParams` keeps `META_TRANSACTION_REF` in the intent's metadata and drops
  `META_USER_ID`; a payment is still traced back to this service's own record by the reference.

This supersedes the logging detail of [0014](0014-transfers-in-one-local-transaction.md), whose Decision and
Consequences describe the missing-recipient refusal as logged with both user ids; `TransferHandler.execute`
logs that refusal with the sender's wallet id and no recipient id. `PaymentService`'s exception messages and
`PaymentTransactionStore` are unaffected: they already resolve ownership through the reference and the row,
never through a logged id.

## Alternatives considered

- Hashing the user id before logging it. Rejected: the id is already opaque and unguessable
  ([0003](0003-caller-identity-and-trust-boundary.md)), so a hash of it is exactly as sensitive as the id itself,
  and it adds code for no reduction in what a reader of the log can do with it.
- Truncating or masking the user id, such as its first eight characters. Rejected: a partial UUID still narrows
  the guess enough to matter for a value this sensitive, and the wallet id or the reference already identifies
  the row without it.
- Keeping the missing-recipient WARN with both ids, since it can also be read as evidence of one caller probing
  for others' wallets. Rejected by the owner: user ids stay out of every log line without exception, and the
  probe itself is still visible from the wallet id, the currency and the attempt count.

## Consequences

- Correlating a wallet-scoped log line to a user takes a database join from `wallets.id` to `wallets.user_id`,
  not a read of the log.
- Stripe's dashboard shows no FlowWallet user for a charge; tracing a payment there goes through
  `transactionReference`, which Payment Service already uses as its primary lookup key.
- `ARCHITECTURE.md`'s identity and transfer sections describe the wallet id and no id instead of the user ids
  that [0014](0014-transfers-in-one-local-transaction.md) described.
- A future log statement that would otherwise carry a user id string routes through the wallet id or the
  `transactionReference` instead.
