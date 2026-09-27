---
paths:
  - "**/pom.xml"
  - "**/flow-wallet-payment/**"
  - "**/flow-wallet-contract/**"
  - "**/*Listener.java"
  - "**/*Mapper.java"
  - "**/wallet/dto/*Response.java"
  - "**/wallet/balance/Wallet.java"
  - "**/wallet/balance/BalanceHistory.java"
  - "**/WalletServiceTest.java"
  - "**/KafkaConsumerConfig.java"
---

# The Maven build, Kafka, MapStruct and Stripe

- Boot 4 moved auto-configuration into starters. Plain `liquibase-core` or `spring-kafka` compiles and then
  silently does nothing; use `spring-boot-starter-liquibase` / `spring-boot-starter-kafka`. Class names written
  as strings in YAML can also rot unnoticed after an upgrade.
- The build passes `-Amapstruct.unmappedTargetPolicy=IGNORE` (root `pom.xml`), so a field renamed on either
  side of a mapper silently drops out of the response. Pin a mapped field with a test that uses the real
  mapper (see `WalletServiceTest`).
- Jackson 3 is `tools.jackson.*`. Messages on the topic are JSON strings, so consumers use
  `StringDeserializer` and parse themselves.
- Stripe minor units come from the explicit table in `StripeCurrencyRules`; never derive them from
  `java.util.Currency` (ISO is wrong for Stripe on MGA and ISK). A server-side PaymentIntent confirm needs
  `return_url`.
- `StripeChargeLimits` (chargeable currencies, minimum charges) is a dated copy of Stripe's currency page, like
  the tables in `StripeCurrencyRules`; update the retrieval date with the lists. Only a 400
  `InvalidRequestException` or a 402 `CardException` from Stripe is a refusal (400); every other
  `StripeException` stays a 502
  ([ADR 0022](../../docs/adr/0022-stripe-charge-rules-checked-before-the-reservation.md)).
