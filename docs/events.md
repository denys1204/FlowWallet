# FlowWallet events

This document covers the Kafka topics FlowWallet uses and the events published on them. For how the wallet
service and payment service fit together, see [ARCHITECTURE.md](../ARCHITECTURE.md). For the REST API and its
error responses, see [docs/api.md](api.md), and for the database schema behind these events, see
[docs/data-model.md](data-model.md).

## Kafka topics & events

| Topic | Partitions | Producer | Consumer |
|-------|-----------|----------|----------|
| `payment.events` | 3 | Payment Service (via outbox) | Wallet Service, group `flow-wallet-service` |
| `payment.events.wallet.DLT` | 3 | Wallet Service error handler | none; inspect or replay by hand |

Both topics are declared as beans (`payment.events` by Payment Service, the dead-letter topic by Wallet
Service), so they are created at startup with the configured partitions and replicas instead of relying on
broker auto-creation. Both services set `spring.kafka.admin.fail-fast`, so neither starts while the broker is
unreachable: without it, a service started too early logs "Could not configure topics" once and never creates
its topic. Both producers use `acks=all` with idempotence enabled.

`payment.events.wallet.DLT` is created with `retention.ms=-1` and keeps every record until an operator
deletes it, because nothing else holds a payment that reached it. Wallet Service sets
`spring.kafka.admin.modify-topic-configs`, so the retention also reaches a dead-letter topic created before
it; on a secured broker its principal needs permission to describe and alter that topic's configuration.
Each record the wallet publishes there is logged at ERROR with its topic, partition, offset and cause, and
increments the Micrometer counter `wallet.consumer.dead.letters` (`/actuator/metrics/wallet.consumer.dead.letters`
on Wallet Service). An alert on that counter rising is the signal that a payment waits for an operator. How to
replay a dead letter is in [ARCHITECTURE.md](../ARCHITECTURE.md#the-wallet-consumer), and the reasoning in
[ADR 0020](adr/0020-wallet-dead-letters-kept-and-counted.md).

`payment.events` is also created with `min.insync.replicas` (`KAFKA_TOPIC_PAYMENT_EVENTS_MIN_INSYNC_REPLICAS`,
default `1`). With more than one replica and the broker default of `1`, the leader alone can acknowledge an
`acks=all` send and lose it with its disk. A three-broker cluster such as the lab uses 3 replicas with
`min.insync.replicas=2`: a send still succeeds with one broker down, and with two down it fails instead of
being acknowledged by the leader alone. Startup fails if the value exceeds the replica count. Kafka applies
topic settings only when it creates the topic, and Payment Service does not set
`spring.kafka.admin.modify-topic-configs`, so for an existing `payment.events` set it or change the topic
with `kafka-configs`.

Payment Service has no dead-letter topic of its own. The `FAILED` rows in `outbox_events` play that role,
because a send fails almost only when the broker is unreachable, and a publish to another topic on the same
broker would fail then too. The wallet's dead-letter topic carries failed consumer records, not outbox rows.

Events are published as JSON strings keyed by `transactionReference`. Each record carries an `eventType`
header set to `PaymentCompletedEvent` or `PaymentFailedEvent`. Consumers dispatch on that header and treat a
record without it as unreadable. `schemaVersion` is currently `1`, and it only changes for a change that
can't be made additively. The contract's rules are in [ADR 0009](adr/0009-payment-event-contract.md).

- `PaymentCompletedEvent` carries `eventId`, `schemaVersion`, `transactionReference`, `providerTransactionId`, `amount`, `currency`, `userId`, `completedAt`.
- `PaymentFailedEvent` carries `eventId`, `schemaVersion`, `transactionReference`, `providerTransactionId`, `amount`, `currency`, `userId`, `reason`, `failedAt`.

`completedAt` and `failedAt` are the `created` time of the Stripe event that settled the payment, not the
time Payment Service processed the webhook, so a delivery that Stripe retried hours later still carries the
original time.

Neither event names a wallet. A wallet is identified by its owner and its currency, which the events already
carry ([ADR 0004](adr/0004-wallet-addressed-by-owner-and-currency.md)).

`reason` is a fixed description written by Payment Service, not the provider's decline message, so don't
branch on it. Consumers deduplicate on `eventId` and never on `transactionReference`. A `PaymentFailedEvent`
isn't terminal: the same reference can later get a `PaymentCompletedEvent` if the payment is retried and
succeeds. Payment Service never creates a failure after a success and emits at most one
`PaymentCompletedEvent` per reference, but since the outbox doesn't guarantee per-key send order, a consumer
can still receive a `PaymentFailedEvent` after the `PaymentCompletedEvent` for the same reference. The wallet
copes with either order, because a failure moves no money.
