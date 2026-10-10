# 0038. UGX is charged in whole units written in hundredths, like ISK

- Status: Accepted, supersedes the UGX exponent of [0023](0023-isk-charged-in-whole-units.md)
- Date: 2026-10-10

## Context

[0023](0023-isk-charged-in-whole-units.md) kept UGX in `StripeCurrencyRules.ZERO_DECIMAL`, sent at exponent 0,
because Stripe's currency page lists UGX as zero-decimal and also describes it in hundredths. It left the question to
Stripe: if Stripe confirmed the other reading, UGX would move to `WHOLE_UNITS_IN_HUNDREDTHS` in both services.

Test-mode `POST /v1/payment_intents` requests answered it on 2026-10-10. They used a card payment method,
the platform's PL account settling in PLN and `Stripe-Version: 2026-06-24.dahlia`, the version stripe-java 33.1.0
pins:

- `amount=5050`, `currency=ugx` was refused: "Currency UGX has become effectively zero-decimal and charged amounts
  must be evenly divisible by 100". ISK with `amount=105050` gets the same refusal.
- `amount=5000` was refused as `amount_too_small`, and the message quoted it as 50.00 USh. ISK with `amount=1000` is
  quoted as kr10.00, and JPY with `amount=50`, a zero-decimal currency, as ¥50.
- `amount=1000000`, confirmed with a test card, settled as a balance transaction of 9.67 PLN at an exchange rate of
  0.000967055, which is 10,000 UGX.

So Stripe reads a UGX amount in hundredths whose decimals must be 00, exactly as it reads ISK. At exponent 0 a deposit
of X UGX asked Stripe for X/100 UGX. Under the default deposit limits (`PAYMENT_MAX_DEPOSIT_AMOUNT`, 10000.00) every
such charge was below Stripe's minimum, so Stripe refused it after the transaction row was reserved, and an amount
that is not a multiple of 100 was refused the same way. With higher limits a charge Stripe accepted was a hundredth
of the amount the wallet credited, because the webhook converts back through the same exponent and the amount check
of [0017](0017-webhooks-verified-before-they-are-read.md) passes.

## Decision

- `StripeCurrencyRules.WHOLE_UNITS_IN_HUNDREDTHS` holds ISK and UGX, and UGX leaves `ZERO_DECIMAL`.
  `StripeCurrencyRules.of("UGX")` returns `CurrencyRule(0, 2)`: no decimals are accepted, and 5000 UGX is sent as
  500000. `StripePaymentStrategy` converts the intent's amount back through the same rule, so the webhook and the
  reconciler compare 10,000 UGX with a stored 10,000 UGX.
- `AmountPrecision` moves UGX to its copy of `WHOLE_UNITS_IN_HUNDREDTHS`. Its accepted scale for UGX stays 0, so
  transfers, withdrawals, balances and responses in UGX are unchanged.
- `StripeCurrencyRules.payoutScale("UGX")` stays 0. `StripeConnectPayouts` converts a payout amount through the same
  rule, so a UGX payout would also be sent in hundredths. The test-mode requests covered charges only, and no payout
  currency is UGX: 1.0 pays out in PLN only.

## Alternatives considered

- Keeping UGX at exponent 0: every UGX charge would stay a hundredth of the amount credited, or be refused by
  Stripe after the reservation.
- Taking UGX out of `StripeChargeLimits.CHARGEABLE`: Stripe charges UGX, so the refusal would have no cause.
- Reading the exponent from `java.util.Currency`: ISO gives UGX no decimals, which is the reading Stripe refuses
  ([0015](0015-currency-precision-and-no-rounding.md) rejects ISO exponents for every currency).

## Consequences

- A UGX deposit is charged the amount the wallet credits. Stripe still refuses an amount below its minimum after
  conversion, as for every currency without an entry in `StripeChargeLimits.MINIMUMS`.
- An intent created at exponent 0 converts back to a hundredth of its row, so the amount check refuses its success
  and nothing is credited. Under the default limits Stripe refused every such intent at creation.
- Stripe's page listed UGX among the zero-decimal currencies when it was read on 2026-09-27. If the page and the
  code disagree, the API's refusal of a UGX amount that is not a multiple of 100 is the check to repeat.
