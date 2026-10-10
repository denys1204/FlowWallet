# 0006. Transactions are short, database-only and entered across a bean boundary

- Status: Accepted, extended by [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md)
- Date: 2026-07-25

## Context

Two flows put a blocking network call in the middle of database work. Payment Service writes a payment row, calls
Stripe, and records what Stripe returned. Wallet Service checks the caller's wallet and then calls Payment Service over
HTTP to start a deposit. A transaction left open across either call holds a pooled connection for the whole round
trip, and each service's Hikari pool has 10 connections unless `DB_POOL_MAX_SIZE` says otherwise. If that transaction
also holds a wallet row lock, every other writer of that wallet waits on a remote system the service does not control,
and no `lock_timeout` ends the wait ([0011](0011-wallet-row-locking.md)).

Spring applies `@Transactional` through a proxy around the bean. A call from one method to another method of the same
class never passes through the proxy, so the annotation on the callee does nothing. Under the default propagation, a
transactional method called from a transactional caller joins the caller's transaction instead of starting its own.
Both facts decide where a transaction can begin and end. They matter most after a constraint violation, which aborts
the transaction in Postgres ([0007](0007-unique-constraints-decide.md)).

## Decision

No blocking network call runs inside a database transaction or while a wallet row lock is held.

- `PaymentService.initiatePayment` is not `@Transactional`. It calls `PaymentTransactionStore.findOwnedBy`, then
  `reserve`, then the provider through `PaymentProviderStrategy.initiatePayment`, then `recordInitiation`. Each store
  method is a short transaction of its own, and none of them is open during the Stripe call.
- `DepositService.start` checks that the wallet exists before it calls `PaymentIntentClient.createIntent`. The check
  is one query through the non-locking `WalletRepository.findByUserIdAndCurrency` with no transaction around it, so
  no connection or row lock is held while Payment Service answers.
- `TransferHandler.execute` and `PaymentEventHandler.credit` are the only transactions that lock wallet rows, and
  they do database work only. A wait on a wallet lock therefore ends when a short transaction does.
- `OutboxMessageSender.processEvent` claims an outbox row and records the outcome of the send in `REQUIRES_NEW`
  updates. The Kafka send runs between the two with no transaction open ([0008](0008-transactional-outbox.md)).
- Both services set `spring.jpa.open-in-view: false`, so no request-scoped `EntityManager` keeps a connection for the
  rest of the request.

The class that orchestrates a flow is not `@Transactional`: `PaymentService`, `DepositService`, `TransferService` and
`PaymentEventListener`. Its transactional steps live in a separate bean that it calls: `PaymentTransactionStore`,
`TransferHandler`, `PaymentEventHandler` and `PaymentEventOutcomeStore`. Every call into a transaction crosses a bean
boundary and goes through the proxy. The orchestrator runs between transactions, which is where it can make a network
call, or read from a fresh transaction once a failed one has rolled back.

A method that needs no transaction has no `@Transactional`. On a self-invoked method the annotation suggests a
guarantee the code does not give.

## Alternatives considered

- One `@Transactional` `initiatePayment` around the provider call. It holds a connection through the Stripe round trip
  for every payment in flight, so ten slow calls would take the whole default pool and leave none for webhooks.
- `@Transactional` helper methods on the orchestrating class. They are self-invoked, so they get no proxy and no
  transaction.
- `@Transactional` on `DepositService.requireWallet`. It is private and called from `start`, so the annotation would
  do nothing, and nothing in the method needs a transaction.
- The locking finder `lockByUserIdAndCurrency` for the deposit pre-check. The read only decides whether to make the
  outbound call. A lock held across that call would queue every credit to the wallet behind a Payment Service or
  provider that might be wedged.
- A `lock_timeout` as the guard against long lock holders. It ends a wait by failing the waiter and leaves the slow
  holder where it is. Waits stay short because holders do only database work.
  [0011](0011-wallet-row-locking.md) covers the setting itself.

## Consequences

- A payment's reserve and its record commit separately. A failure between them leaves a reserved row with no provider
  id, and a retry with the same key reuses that row instead of writing a second one. The reference is Stripe's
  idempotency key, so the repeated call creates no second intent ([0013](0013-deposit-initiation.md)).
- Each flow is split across two beans. Moving a transactional method into the class that calls it silently drops its
  transaction. Annotating an orchestrator makes the inner transaction join the outer one, so the network call runs
  inside a transaction and reads after a violation run on the aborted one.
- The lock rules in [0011](0011-wallet-row-locking.md) depend on this decision. A network call added to
  `TransferHandler` or `PaymentEventHandler` would stretch every wallet lock wait to the length of that call, with
  nothing to end it.
