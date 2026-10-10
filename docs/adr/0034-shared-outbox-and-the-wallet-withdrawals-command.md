# 0034. A withdrawal reaches Payment Service as a command on wallet.withdrawals, through a shared outbox module

- Status: Accepted, extends [0002](0002-module-boundaries.md), [0008](0008-transactional-outbox.md) and
  [0009](0009-payment-event-contract.md)
- Date: 2026-10-10

## Context

Once the debit of [0033](0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md) commits, Payment
Service has to learn of the withdrawal at least once, and nothing after the debit may answer the client with an error.
The debit lives in `wallet_db` and the payout in `payment_db`, and no transaction spans both
([0002](0002-module-boundaries.md)). It is the problem [0008](0008-transactional-outbox.md) solves for payment events,
in the other direction.

The outbox exists only in Payment Service: `OutboxEvent`, `OutboxEventRepository` with the claim queries of
[0018](0018-outbox-sends-own-their-claim.md), `OutboxMessageSender`, `OutboxPoller` with its reaper and cleanup,
`OutboxEventListener`, `OutboxProperties`, the `outbox.events.failed` gauge and the `outbox` actuator endpoint.
`PaymentOutboxService.enqueue` takes a `PaymentTransaction`, so it cannot carry another aggregate. The services may
share only `flow-wallet-platform`, which is servlet plumbing with nothing domain-shaped, and `flow-wallet-contract`,
which has no dependencies. The wallet runs no scheduled jobs and has no `@EnableScheduling`.

## Decision

A withdrawal crosses to Payment Service as an event. The debit transaction in `WithdrawalHandler` writes an
`outbox_events` row carrying `WithdrawalRequestedEvent`. The wallet's outbox publishes it to the topic
`wallet.withdrawals`, keyed by the reference, and a consumer in Payment Service accepts it. The outcomes come back on
`payment.events` as payout events ([0033](0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md)).
After the request, the two services exchange withdrawals only through Kafka.

The outbox lives in a sixth module, `flow-wallet-outbox`, and both services depend on it. The module holds the classes
listed above and their tests, so the claim, retry, reaper, cleanup and dead-letter rules of
[0008](0008-transactional-outbox.md) and [0018](0018-outbox-sends-own-their-claim.md) hold in both services as written.
The entry point is `enqueue(aggregateType, aggregateId, eventType, payload)`. The topic a service publishes to comes
from an `OutboxTopic` bean that the service declares with the contract's constant, and startup fails without one. It is
not a property, because the environment can override any property (`OUTBOX_TOPIC` would), and topic names are
compile-time constants. An auto-configuration carrying
`@AutoConfigurationPackage` and ordered before the JPA auto-configurations adds the module's entity and repository to
each service's own scan. Payment Service keeps a thin `PaymentOutboxService` that builds its events and calls `enqueue`.
The aggregate type is a constant of the service that writes the row, `Payout` for payout events. Nothing
withdrawal-specific is in the module.

`OutboxEventListener` sends after the commit through `@Async`. The module's auto-configuration carries `@EnableAsync`,
so in both services the after-commit send runs on the task executor and never on the request thread, where
`OutboxMessageSender` would wait on the broker for as long as the producer's `max.block.ms` and `delivery.timeout.ms`
allow.

The wallet has an `outbox_events` table (migration `012-create-outbox-events`) with the schema of Payment Service's
changesets 002 to 004, and it runs scheduled jobs. A configuration class with `@EnableScheduling` behind
`@ConditionalOnProperty` switches them on, one switch per service as in Payment Service's `SchedulingConfig`
([0032](0032-pending-payments-are-rechecked-with-the-provider.md)), so an integration test can run with every job off.
The wallet's scheduler pool has at least two threads.

`flow-wallet-contract` holds the topic name `wallet.withdrawals` and `WithdrawalRequestedEvent` with its header value.
The event carries `eventId` and `schemaVersion`, like the payment events, and the terms Payment Service binds to the
reference: `transactionReference`, `userId`, `amount` and `currency`. It carries no wallet id
([0004](0004-wallet-addressed-by-owner-and-currency.md)), and its amount is a JSON number
([0030](0030-amounts-in-responses-are-decimal-strings.md)). The evolution rules of
[0009](0009-payment-event-contract.md) apply to it, and a wire-format test pins it on each side. The contract runs both
ways: the wallet writes one topic and reads the other, and Payment Service does the opposite.

Payment Service consumes `wallet.withdrawals` with a configuration modelled on the wallet's consumer
([0010](0010-idempotent-payment-event-consumer.md), [0020](0020-wallet-dead-letters-kept-and-counted.md)). An
unreadable record, or one whose shape is invalid (a missing field, a reference that is not a UUID, an amount that is
not positive or off the grid), goes to `wallet.withdrawals.payment.DLT` at once, because a retry cannot change it. Any
other failure is retried by the `DefaultErrorHandler` and then dead-lettered.

The producer owns its topic, and each topic is declared as a `NewTopic` bean. Wallet Service declares
`wallet.withdrawals`. Payment Service, which consumes it and publishes its dead letters, declares
`wallet.withdrawals.payment.DLT` with `retention.ms=-1`, so the topic keeps its records as the wallet's does in
[0020](0020-wallet-dead-letters-kept-and-counted.md). The topic is created with that setting, so Payment Service leaves
`spring.kafka.admin.modify-topic-configs` off, and an existing `payment.events` keeps its own settings. Payment Service
is deployed first, before `wallet.withdrawals` exists. Its listener does not treat a missing topic as fatal, and it
starts from the earliest offset, as the wallet's consumer does, so it reads the first commands once Wallet Service has
created the topic. The statement in [0008](0008-transactional-outbox.md) that Payment Service has no Kafka dead-letter
topic concerns its outbox sends, whose dead-letter store is the `FAILED` rows.

Accepting a command inserts a `payouts` row in state `ACCEPTED` under the reference
([0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md)), and the unique reference decides a repeat
([0007](0007-unique-constraints-decide.md)). A command with the same terms (user id, amount by `compareTo`, currency
ignoring case) is acknowledged as a redelivery. One with other terms is a defect, logged at ERROR and dead-lettered at
once. Whether a command is accepted depends only on its shape and its reference, never on configuration. The terms
that configuration sets (the currency list, the minimum, the payout grid) are checked again in the same transaction: a
command that no longer meets them, such as one for a currency taken off the list or below a minimum raised after its
preflight, is inserted and moved straight to `RETURNED` with the reason `TERMS_REFUSED`, and its `PayoutReturnedEvent`
is written with it. Once a withdrawal is debited, its money comes back by that one path.

A command that does not reach a `payouts` row leaves its money debited in the wallet until an operator acts, and
each way it can stop has one recovery:

- A record on `wallet.withdrawals.payment.DLT` comes from a defect, such as an unreadable record, or from an outage
  that outlasted the retries, such as a `payment_db` outage longer than the backoff. Once the cause is fixed, an
  operator republishes the record unchanged to `wallet.withdrawals`. Accepting is idempotent on the reference, so a
  command that was already accepted is acknowledged as a redelivery. Each dead letter is logged at ERROR and counted,
  as in [0020](0020-wallet-dead-letters-kept-and-counted.md).
- A `FAILED` row in the wallet's outbox is a debited withdrawal that Payment Service never received. An operator
  requeues it through the wallet's `outbox` actuator endpoint, on Wallet Service's own port, which the gateway does not
  route. Wallet Service lists `outbox` in its default `ACTUATOR_EXPOSED_ENDPOINTS`, as Payment Service does, because
  the requeue is the only exit short of SQL. The endpoint only returns `FAILED` rows to `PENDING`, so it can resend a
  command the wallet debited but cannot create one, and a requeued command is accepted once because the reference is
  unique.

The consumer ships and is deployed before the producer, as the wallet's consumer is for payment events, and the
wallet's withdrawal endpoint is switched on last.

## Alternatives considered

- An HTTP hand-off after the commit to an internal `POST /api/payments/payouts`, with the withdrawal row as its own
  outbox and a `WithdrawalHandoffSweeper` resending what Payment Service had not acknowledged. It needs hand-off
  columns on `withdrawals`, a timeout and backoff of its own, and a blocked state for a 400 or 409 from Payment
  Service that left debited money with no way out. Payment Service has to be up for the hand-off to finish, where
  Kafka holds the command until it is.
- A `withdrawal_handoffs` table sent by an `@Async` listener and a poller over internal HTTP. It is a second delivery
  mechanism beside the outbox and guarantees nothing the outbox does not.
- A copy of the outbox in the wallet. About 700 lines of code and as many of tests, and the claim rules of
  [0018](0018-outbox-sends-own-their-claim.md) would live in two places, and a fix to one copy would have to reach the
  other by hand.
- The outbox in `flow-wallet-platform`. Platform would take on persistence and Kafka and stop being plumbing with
  nothing domain-shaped ([0002](0002-module-boundaries.md)).
- The outbox in `flow-wallet-contract`. The contract has no dependencies by design.
- An `outbox.topic` property that each service sets in code. An environment variable still overrides it, so the topic
  would be configuration after all.
- `@EntityScan` in the module's auto-configuration. It replaces a service's entity scan instead of adding to it, and
  the service's own entities disappear.
- Refusing a command whose terms the configuration no longer allows, with a dead letter. The money would stay debited
  until an operator acted, although a return through the event path is always possible.

## Consequences

- The debit and the command commit together. A Payment Service that is down delays a withdrawal without failing it:
  the command waits in the outbox or on the topic and is accepted when the consumer is back.
- The wallet's outbox has the properties of [0008](0008-transactional-outbox.md): at-least-once delivery, duplicates
  that share an event id, `FAILED` rows as the dead-letter store, the `outbox.events.failed` gauge and the `outbox`
  actuator endpoint, which both services expose by default.
- The reactor has six modules. The services never depend on each other, and code both need goes to contract, platform
  or outbox.
- A dead-lettered or `FAILED` command keeps its money debited in the wallet until an operator republishes or requeues
  it.
- Each direction has its own dead-letter topic, owned by its consumer: `payment.events.wallet.DLT` and
  `wallet.withdrawals.payment.DLT`.
- Whatever can write to `wallet.withdrawals` asks for a payout with no debit behind it. The network boundary in
  [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md) covers the broker as well as Payment Service's port.
