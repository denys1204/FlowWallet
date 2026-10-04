---
paths:
  - "**/pom.xml"
  - "**/flow-wallet-payment/**"
  - "**/flow-wallet-contract/**"
  - "**/*Listener.java"
  - "**/*Mapper.java"
  - "**/KafkaConsumerConfig.java"
---

# The Maven build, Kafka, MapStruct and Stripe

- Boot 4 moved auto-configuration into starters. Plain `liquibase-core` or `spring-kafka` compiles and then
  silently does nothing; use `spring-boot-starter-liquibase` / `spring-boot-starter-kafka`. Class names written
  as strings in YAML can also rot unnoticed after an upgrade.
- The build passes `-Amapstruct.unmappedTargetPolicy=IGNORE` (root `pom.xml`), so a field renamed on either
  side of a mapper silently drops out of its target. Pin a mapped field with a test that uses the real
  mapper (see `PaymentEventMapperTest`). Wallet Service has no mapper: its responses are built by static
  factories, because MapStruct would turn a `BigDecimal` into a `String` with `toString`, at the ledger's scale
  ([ADR 0030](../../docs/adr/0030-amounts-in-responses-are-decimal-strings.md)).
- Jackson 3 is `tools.jackson.*`. Messages on the topic are JSON strings, so consumers use
  `StringDeserializer` and parse themselves.
- Stripe minor units come from the explicit table in `StripeCurrencyRules`; never derive them from
  `java.util.Currency` (ISO is wrong for Stripe on MGA and ISK).
- A deposit intent lists its payment methods (`stripe.payment-method-types`, cards only) instead of enabling
  automatic ones, so a server-side confirm needs no `return_url`
  ([ADR 0028](../../docs/adr/0028-deposits-accept-cards-only.md)).
- `StripeChargeLimits` (chargeable currencies, minimum charges) is a dated copy of Stripe's currency page, like
  the tables in `StripeCurrencyRules`; update the retrieval date with the lists. Only a 400
  `InvalidRequestException` or a 402 `CardException` from Stripe is a refusal (400); every other
  `StripeException` stays a 502
  ([ADR 0022](../../docs/adr/0022-stripe-charge-rules-checked-before-the-reservation.md)).
- Test values for Stripe keys and signing secrets are visibly fake and break the key shape with hyphens
  (`whsec_unit-test-signing-secret`, not a `whsec_`/`sk_test_` prefix followed by a long alphanumeric run).
  GitHub secret scanning flags anything shaped like a real key in this public repository, including the
  example keys from Stripe's own docs.
