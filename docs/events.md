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
| `wallet.withdrawals` | 3 | Wallet Service (via outbox) | Payment Service, group `flow-wallet-payment` |
| `wallet.withdrawals.payment.DLT` | 3 | Payment Service error handler | none; inspect or republish by hand |

Every topic is declared as a bean by the service that owns it (`payment.events` and `wallet.withdrawals.payment.DLT` by
Payment Service, `payment.events.wallet.DLT` and `wallet.withdrawals` by Wallet Service), so they are created at startup
with the configured partitions and replicas instead of relying on broker auto-creation. Both services set
`spring.kafka.admin.fail-fast`, so neither starts while the broker is unreachable: without it, a service started too
early logs "Could not configure topics" once and never creates its topic. Both producers use `acks=all` with idempotence
enabled.

`payment.events.wallet.DLT` is created with `retention.ms=-1` and keeps every record until an operator
deletes it, because nothing else holds a payment that reached it. Wallet Service sets
`spring.kafka.admin.modify-topic-configs`, so the retention also reaches a dead-letter topic created before
it; on a secured broker its principal needs permission to describe and alter that topic's configuration.
Each record the wallet publishes there is logged at ERROR with its topic, partition, offset and cause, and
increments the Micrometer counter `wallet.consumer.dead.letters` (`/actuator/metrics/wallet.consumer.dead.letters`
on Wallet Service). An alert on that counter rising is the signal that a payment waits for an operator. How to
replay a dead letter is in [ARCHITECTURE.md](../ARCHITECTURE.md#the-wallet-consumer), and the reasoning in
[ADR 0020](adr/0020-wallet-dead-letters-kept-and-counted.md).

`payment.events` is also created with `min.insync.replicas` (`KAFKA_TOPIC_PAYMENT_EVENTS_MIN_INSYNC_REPLICAS`, default
`1`). With more than one replica and the broker default of `1`, the leader alone can acknowledge an `acks=all` send and
lose it with its disk. A three-broker cluster such as the lab uses 3 replicas with `min.insync.replicas=2`: a send still
succeeds with one broker down, and with two down it fails instead of being acknowledged by the leader alone. Startup
fails if the value exceeds the replica count. Kafka applies topic settings only when it creates the topic, and Payment
Service does not set `spring.kafka.admin.modify-topic-configs`, so for an existing `payment.events` set it or change the
topic with `kafka-configs`. `wallet.withdrawals` takes the same setting
(`KAFKA_TOPIC_WALLET_WITHDRAWALS_MIN_INSYNC_REPLICAS`, default `1`) and fails startup the same way. Wallet Service sets
`spring.kafka.admin.modify-topic-configs`, so a changed value also reaches an existing topic.

Payment Service's outbox has no dead-letter topic of its own. The `FAILED` rows in `outbox_events` play that role,
because a send fails almost only when the broker is unreachable, and a publish to another topic on the same broker would
fail then too. The wallet's dead-letter topic carries failed consumer records, not outbox rows. Wallet Service's outbox
works the same way for `wallet.withdrawals`: its `FAILED` rows are the dead-letter store for withdrawal commands.

Payment Service consumes `wallet.withdrawals` and accepts each command by inserting its payout under the unique
reference. A redelivery with the same terms is acknowledged. A command with other terms under an accepted reference, an
unreadable record and a command of an invalid shape go to `wallet.withdrawals.payment.DLT` at once; any other failure is
retried first (`PAYMENT_CONSUMER_RETRY_*`). The topic is created with `retention.ms=-1`, each dead letter is logged at
ERROR and counted in `payment.consumer.dead.letters`, and a record there is a debited withdrawal that waits for an
operator to republish it unchanged to `wallet.withdrawals` once the cause is fixed. The consumer sets
`allow.auto.create.topics=false`, so only Wallet Service creates `wallet.withdrawals`, with its declared partitions
([ADR 0034](adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md)).

Events are published as JSON strings keyed by `transactionReference`. Each record carries an `eventType` header set to
the name of its event, such as `PaymentCompletedEvent` or `PaymentFailedEvent`. Consumers dispatch on that header and
treat a record without it as unreadable. `schemaVersion` is currently `1`, and it only changes for a change that can't
be made additively. The contract's rules are in [ADR 0009](adr/0009-payment-event-contract.md).

- `PaymentCompletedEvent` carries `eventId`, `schemaVersion`, `transactionReference`, `providerTransactionId`, `amount`, `currency`, `userId`, `completedAt`.
- `PaymentFailedEvent` carries `eventId`, `schemaVersion`, `transactionReference`, `providerTransactionId`, `amount`, `currency`, `userId`, `reason`, `failedAt`.

`completedAt` and `failedAt` are the `created` time of the Stripe event that settled the payment, not the
time Payment Service processed the webhook, so a delivery that Stripe retried hours later still carries the
original time. A payment the reconciler settled has no such event: its `completedAt` is when the reconciler saw it
succeed, and its `failedAt` is when Stripe canceled the intent
([ADR 0032](adr/0032-pending-payments-are-rechecked-with-the-provider.md)).

Neither event names a wallet. A wallet is identified by its owner and its currency, which the events already
carry ([ADR 0004](adr/0004-wallet-addressed-by-owner-and-currency.md)).

`reason` is a fixed description written by Payment Service, not the provider's decline message, so don't
branch on it. Consumers deduplicate on `eventId` and never on `transactionReference`. A `PaymentFailedEvent`
isn't terminal: the same reference can later get a `PaymentCompletedEvent` if the payment is retried and
succeeds. Payment Service never creates a failure after a success and emits at most one
`PaymentCompletedEvent` per reference, but since the outbox doesn't guarantee per-key send order, a consumer
can still receive a `PaymentFailedEvent` after the `PaymentCompletedEvent` for the same reference. The wallet
copes with either order, because a failure moves no money.

## Withdrawal events

The contract also defines the events of a withdrawal
([ADR 0033](adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md),
[ADR 0034](adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md)). Payment Service consumes the commands and
Wallet Service the payout events. Payment Service sends `PayoutReturnedEvent` for a command whose terms it refuses and
when the payout executor or an operator returns a payout
([ADR 0037](adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md)), `PayoutFailedEvent` when a
reversal starts, and `PayoutPaidEvent` when the Connect webhook or the payout reconciler finds the payout paid
([ADR 0036](adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md)). Stripe can fail a payout days after it
was paid, so a `PayoutFailedEvent` can follow the `PayoutPaidEvent` for the same reference. `paidAt` is when Payment
Service saw the payout paid, not Stripe's arrival date. Wallet Service writes a command through its outbox in the
transaction that debits the wallet. They shipped in this order: the contract, the consumers, then the producers, so no
consumer met an event type it did not know.

- `WithdrawalRequestedEvent`, on `wallet.withdrawals` from Wallet Service to Payment Service, carries `eventId`, `schemaVersion`, `transactionReference`, `userId`, `amount`, `currency`.
- `PayoutPaidEvent`, on `payment.events`, carries `eventId`, `schemaVersion`, `transactionReference`, `providerPayoutId`, `amount`, `currency`, `userId`, `paidAt`.
- `PayoutFailedEvent`, on `payment.events`, carries `eventId`, `schemaVersion`, `transactionReference`, `providerPayoutId`, `amount`, `currency`, `userId`, `reasonCode`, `providerFailureCode`, `failedAt`.
- `PayoutReturnedEvent`, on `payment.events`, carries `eventId`, `schemaVersion`, `transactionReference`, `providerPayoutId`, `amount`, `currency`, `userId`, `reasonCode`, `returnedAt`.

Each record is keyed by `transactionReference` and carries the `eventType` header with the event's name, like the
payment events. `providerPayoutId` is required on `PayoutPaidEvent` and null on a failure or a return when no payout was
created. `providerFailureCode` is null when Stripe gave none. `reasonCode` is a string from the open set in
[ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md), never an enum, so a consumer stores it and does
not branch on it. Only `PayoutReturnedEvent` moves money, which is why a consumer does not depend on the order of payout
events.
