# 0020. The wallet's dead-letter topic keeps its records without a time limit, and every dead letter is counted

- Status: Accepted, extends [0010](0010-idempotent-payment-event-consumer.md)
- Date: 2026-09-27

## Context

In [0010](0010-idempotent-payment-event-consumer.md) every record the wallet consumer cannot read or settle ends on
`payment.events.wallet.DLT`, which has no consumer and is replayed by hand. The topic was declared without a
retention setting, so the broker default of seven days applied. Payment Service deletes `COMPLETED` outbox rows
after `outbox.retention-days` (seven by default), and `DeadLetterPublishingRecoverer` stamps each dead letter with
the time it is published. A dead letter nobody replayed within a week was therefore deleted, and the only trace of a
payment the customer was charged for was its `SUCCESS` row in `payment_transactions`, which nothing reconciles.

Nothing signalled a dead letter either. The error handler's retry listener logged each failed attempt at WARN with the
container's wrapper exception, which names neither the partition nor the actual cause.

Kafka applies topic settings only when it creates a topic, and `KafkaAdmin` creates declared topics once at startup.
With Spring Boot's default `spring.kafka.admin.fail-fast: false`, a service that starts while the broker is
unreachable logs "Could not configure topics" and never retries. On a broker with auto-creation off, the dead-letter
topic then never exists, and every dead-letter publish fails and has its record redelivered without end.

## Decision

- `KafkaConsumerConfig` declares the dead-letter topic with `retention.ms=-1`, so a record stays until an operator
  deletes it.
- Wallet Service sets `spring.kafka.admin.modify-topic-configs: true`, so the retention reaches a dead-letter topic
  that already exists.
- Both services set `spring.kafka.admin.fail-fast: true`: a service does not start until the broker has confirmed
  its declared topics, `payment.events` for Payment Service and the dead-letter topic for Wallet Service.
- `PaymentEventRetryListener` is the error handler's retry listener. It logs each failed attempt at WARN and each
  record published to the dead-letter topic at ERROR, both with the topic, partition, offset and the most specific
  cause (`NestedExceptionUtils.getMostSpecificCause`). Each successful dead-letter publish increments the Micrometer
  counter `wallet.consumer.dead.letters`. A publish that fails is logged at ERROR and not counted, because the record
  stays on `payment.events` and is redelivered.
- All three are literal wiring in `application.yml`, not environment variables.

## Alternatives considered

- A long finite retention, such as 90 days: it moves the deadline without removing it, and the one record of a
  charged payment still disappears if nobody acts in time.
- A consumer that copies dead letters into a `wallet_db` table: a second store and a second failure path for records
  that already failed once, while the topic is already durable once retention is unlimited.
- Counting failed attempts instead of dead letters: a momentary database outage would raise the counter for records
  that were credited on a later attempt.
- Counting in a wrapper around `DeadLetterPublishingRecoverer`: the retry listener's `recovered` callback runs only
  after a publish succeeds, so it already separates a delivered dead letter from a failed publish.
- Retrying topic creation in the background instead of failing startup: a consumer would run before the topic it
  depends on exists.

## Consequences

- A dead letter stays on the topic until an operator deletes it, so the topic grows by one record per dead letter
  until then.
- An alert on `wallet.consumer.dead.letters` rising is the signal that a payment waits for an operator.
- Neither service starts while Kafka is unreachable, so local work starts Compose first.
- With `modify-topic-configs`, Wallet Service's Kafka principal on a secured broker needs permission to describe and
  alter the dead-letter topic's configuration, or startup fails.
