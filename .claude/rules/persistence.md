---
paths:
  - "**/db/changelog/**"
  - "**/application*.yml"
  - "**/*Properties.java"
  - "**/.env.example"
  - "**/OutboxPoller.java"
  - "**/PaymentTransactionHandler.java"
  - "**/KafkaConfig.java"
  - "**/KafkaConsumerConfig.java"
---

# Migrations and configuration

- Liquibase: `db.changelog-master.yaml` uses `includeAll`; files are `NNN-description.yaml`, author
  `flow-wallet`, sequences `incrementBy: 50`. `addCheckConstraint` is Liquibase Pro; use a raw `sql:` change.
- Tunable values are `${ENV_VAR:working-local-default}` in `application.yml`; fixed wiring (serializers,
  `acks: all`, `ack-mode: RECORD`, `ddl-auto: validate`) stays literal. The webhook signing secret has no working
  default: `stripe.webhook.secret` is `${STRIPE_WEBHOOK_SECRET:}`, and an empty value disables webhooks
  ([ADR 0017](../../docs/adr/0017-webhooks-verified-before-they-are-read.md)). New settings go in a
  `@ConfigurationProperties` class with `@Validated` checks that fail startup on nonsense, like
  `PaymentDepositProperties`, `StripeProperties`, `WalletPaymentProperties` or `OutboxProperties`. Older ones are
  not there yet: outbox schedules, the optimistic-lock retry and topic partitions/replicas are read through
  `@Scheduled`/`@Retryable`/`@Value` placeholders. New variables go in
  `.env.example` too. Topic names are compile-time constants, not config.
