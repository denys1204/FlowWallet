# 0009. Payment events evolve additively, are typed by header, deduplicated on eventId and need no ordering

- Status: Accepted
- Date: 2026-07-25

## Context

Payment Service publishes `PaymentCompletedEvent` and `PaymentFailedEvent` to `payment.events`, and Wallet Service
consumes them to credit balances. The two services are deployed separately, so the topic always holds messages written
by more than one version of the code. A change the compiler accepts can still break a consumer that has not been
upgraded. Delivery is at least once and send order per `transactionReference` is not guaranteed
([0008](0008-transactional-outbox.md)).

One payment can produce both events. A declined card leaves the Stripe PaymentIntent usable, so a customer can retry the
same intent and succeed after an earlier failure. A consumer therefore has to tell a redelivered message apart from a
second, different event for the same payment, and it cannot count on the order in which the two arrive.

## Decision

The evolution rules sit in the `package-info` of `flow-wallet-contract`:

- add optional fields only;
- never rename, remove or retype a field in place; add the replacement and drop the old one once every consumer has
  moved;
- keep enums off the wire.

Consumers ignore fields they do not know. `schemaVersion` comes from `KafkaConstants.PAYMENT_EVENT_SCHEMA_VERSION`
and changes only for a change that cannot be made additively. Adding an optional field leaves it alone.

Every record carries the `eventType` header (`KafkaConstants.HEADER_EVENT_TYPE`), set to `PaymentCompletedEvent` or
`PaymentFailedEvent`. Consumers dispatch on the header and never infer the type from the fields present in the JSON. A
record whose header is missing or holds an unknown value is unreadable (`UnreadablePaymentEventException`). The payload
is a JSON string keyed by `transactionReference`. Payment Service sends it with `StringSerializer`, and Wallet Service
reads it with `StringDeserializer` and parses it itself. The contract module has no dependencies and ships no
serializer.

`PaymentEventMapper` generates `eventId` as a UUID when it builds the event for the outbox row. The row stores the
serialized payload and the sender publishes it as stored, so every redelivery and replay carries the same `eventId`.
Consumers deduplicate on `eventId`. They never deduplicate on `transactionReference`, which names the payment rather
than the message. In the wallet, `transactionReference` is the credit barrier instead, allowing at most one `DEPOSIT`
per reference ([0010](0010-idempotent-payment-event-consumer.md)).

The producer's state machine in `PaymentTransaction` decides which events exist. `PaymentTransactionHandler` writes an
event only when `markAsSuccess` or `markAsFailed` returns `true`, which happens only on a real state change:

- SUCCESS is terminal, and a repeated success changes nothing;
- FAILED can be promoted to SUCCESS, because Stripe can retry the same PaymentIntent and succeed;
- only a PENDING transaction can fail, so a second failure, or a failure after a success, changes nothing.

So each reference yields at most one `PaymentCompletedEvent`, and no failure is created after a success.

The outbox does not guarantee per-key send order, so a consumer can still receive a `PaymentFailedEvent` after the
`PaymentCompletedEvent` for the same reference. A `PaymentFailedEvent` moves no money, so neither event can undo the
other, and the consumer has no ordering logic. `reason` is a fixed description written by Payment Service, not the
provider's decline message, and consumers do not branch on it.

## Alternatives considered

- Bumping `schemaVersion` on every change, including an added optional field. Consumers already ignore unknown fields,
  so the bump would announce a break that never happened.
- Renaming or removing a field in place. A consumer that has not moved finds the field missing, and the topic keeps
  holding messages in the old shape.
- Enums on the wire. An unknown constant fails deserialization on every older consumer.
- Inferring the event type from the fields present in the JSON. It breaks on the first schema change.
- A serializer shipped in the contract module. It pulls a framework into a module whose point is to have no
  dependencies.
- Deduplicating on `transactionReference`. It swallows the success that follows a retried failure and leaves the
  customer charged with no credit.
- Treating `PaymentFailedEvent`, or a FAILED transaction, as terminal. It drops a genuine recovery.
- Emitting an event on every webhook regardless of state change. A late or repeated failure webhook would flip a
  settled payment to FAILED, and a repeated success would publish a second `PaymentCompletedEvent`.
- Ordering or sequencing logic per reference in the consumer. Only one of the two events moves money, so there is
  nothing for it to protect.
- Guaranteeing per-key send order in the outbox ([0008](0008-transactional-outbox.md)).

## Consequences

- An additive change can ship with either the producer or the consumer going first.
- Unknown fields are ignored because Jackson 3 leaves `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` off by
  default and neither service turns it on. Turning it on would make every additive change a breaking one.
- Wallet Service does not read `schemaVersion`. Before the producer ships the first non-additive change, the consumer
  has to learn to branch on it.
- `eventId` has to stay attached to the outbox row. If the sender generated it at send time, each redelivery would get
  a fresh id and deduplication would stop working.
- A new event type needs a header value in `KafkaConstants` and a case in `PaymentEventListener`. Until the consumer
  has that case, it sends such records to its dead-letter topic without retrying them.
- If a producer defect ever creates a second `PaymentCompletedEvent` for one reference, `eventId` cannot catch it. The
  wallet's reference barrier refuses it as `DUPLICATE_REFERENCE`.
