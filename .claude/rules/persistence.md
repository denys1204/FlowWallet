---
paths:
  - "**/db/changelog/**"
  - "**/application*.yml"
  - "**/*Properties.java"
  - "**/.env.example"
  - "**/OutboxPoller.java"
  - "**/PendingPaymentReconciler.java"
  - "**/PaymentTransactionHandler.java"
  - "**/KafkaConfig.java"
  - "**/KafkaConsumerConfig.java"
---

# Migrations and configuration

- Liquibase: `db.changelog-master.yaml` uses `includeAll`; files are `NNN-description.yaml`, author
  `flow-wallet`, sequences `incrementBy: 50`. `addCheckConstraint` is Liquibase Pro; use a raw `sql:` change.
- Tunable values are `${ENV_VAR:working-local-default}` in `application.yml`; fixed wiring (serializers, `acks: all`,
  `ack-mode: RECORD`, `ddl-auto: validate`, `spring.kafka.admin.fail-fast` and, in the wallet, `modify-topic-configs`
  and `auto-offset-reset: earliest`) stays literal. The webhook signing secret has no working default:
  `stripe.webhook.secret` is `${STRIPE_WEBHOOK_SECRET:}`, and an empty value disables webhooks
  ([ADR 0017](../../docs/adr/0017-webhooks-verified-before-they-are-read.md)). New settings go in a
  `@ConfigurationProperties` class with `@Validated` checks that fail startup on nonsense, like
  `PaymentDepositProperties`, `StripeProperties`, `WalletPaymentProperties`, `OutboxProperties` or
  `PaymentEventsTopicProperties`. Older ones are not there yet: outbox schedules, the optimistic-lock retry and the
  wallet dead-letter topic's partitions/replicas are read through `@Scheduled`/`@Retryable`/`@Value` placeholders. A
  switch that turns a bean on or off is read by `@ConditionalOnProperty`, which runs before binding, so
  `payment.scheduling.enabled` (`SchedulingConfig`) has no properties class, and such a switch's default also sits in
  `matchIfMissing`. The reconciler's interval is checked in `PaymentReconciliationProperties`, but `@Scheduled` reads
  it through a placeholder, so its default sits in the YAML, the properties class and the annotation. New variables go
  in `.env.example` too. Topic names are compile-time constants, not config.
