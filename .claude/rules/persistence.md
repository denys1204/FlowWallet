---
paths:
  - "**/db/changelog/**"
  - "**/application*.yml"
  - "**/*Properties.java"
  - "**/.env.example"
---

# Migrations and configuration

- Liquibase: `db.changelog-master.yaml` uses `includeAll`; files are `NNN-description.yaml`, author
  `flow-wallet`, sequences `incrementBy: 50`. `addCheckConstraint` is Liquibase Pro; use a raw `sql:` change.
- Tunable values are `${ENV_VAR:working-local-default}` in `application.yml`; fixed wiring (serializers,
  `acks: all`, `ack-mode: RECORD`, `ddl-auto: validate`) stays literal. New settings go in a
  `@ConfigurationProperties` class with `@Validated` checks that fail startup on nonsense, like
  `PaymentDepositProperties` or `WalletPaymentProperties`. Older ones are not there yet: `OutboxProperties` and
  `StripeProperties` are unvalidated, and outbox schedules, the optimistic-lock retry and topic
  partitions/replicas are read through `@Scheduled`/`@Retryable`/`@Value` placeholders. New variables go in
  `.env.example` too. Topic names are compile-time constants, not config.
