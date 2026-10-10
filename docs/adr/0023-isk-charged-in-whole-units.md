# 0023. ISK is charged in whole units written in hundredths

- Status: Accepted, supersedes the ISK precision of [0015](0015-currency-precision-and-no-rounding.md), extended by
  [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md)
- Date: 2026-09-27

## Context

[0015](0015-currency-precision-and-no-rounding.md) gives each currency two numbers in `StripeCurrencyRules`: the
decimals an amount may carry and the power of ten that converts it for Stripe. ISK had the default rule, two
decimals and exponent 2, and `AmountPrecision` copied the two decimals, so a deposit or a transfer of 10.50 ISK was
accepted. Stripe's currency page (https://docs.stripe.com/currencies, retrieved on 2026-09-27) says that ISK became
a zero-decimal currency but is still written as a two-decimal value whose decimals are always 00, and that ISK
cannot be charged in fractions. A deposit of 10.50 ISK went to Stripe as 1050 after its row was reserved, and
Stripe refused it.

The same page also describes UGX in hundredths with the decimals always 00, while listing UGX among the
zero-decimal currencies whose amount is sent as it is. HUF and TWD are written in whole units, divisible by 100,
only for payouts; their charges take two decimals.

## Decision

- `StripeCurrencyRules.WHOLE_UNITS_IN_HUNDREDTHS` holds ISK. `StripeCurrencyRules.of("ISK")` returns
  `CurrencyRule(0, 2)`: no decimals are accepted, and 10 ISK is sent as 1000. `StripeRequestMapper.validate`
  refuses 10.50 ISK with `InvalidPaymentRequestException` (400) before the reservation.
- `AmountPrecision` keeps a copy of that list, so transfers and payment events in ISK are held to whole units too.
  The two lists point at each other, like the zero-decimal lists.
- UGX stays in `ZERO_DECIMAL`, with no decimals accepted and exponent 0. Stripe's page contradicts itself about
  the exponent. Both readings forbid fractions, which the table already does, and changing the exponent on an
  ambiguous source risks charging a hundred times the amount or a hundredth of it.
- HUF and TWD keep two decimals and exponent 2, because their whole-unit rule applies only to payouts, which
  FlowWallet does not make.

## Alternatives considered

- ISK in `ZERO_DECIMAL`, sent at exponent 0: 10 ISK would go out as 10, which Stripe reads as 0.10 ISK, a
  hundredth of the amount.
- Keeping two decimals for ISK and letting Stripe refuse fractions: the refusal comes after the reservation, so
  the caller loses the key, and the wallet could still create fractional ISK by transfer.
- Rounding a fractional ISK amount to whole units: [0015](0015-currency-precision-and-no-rounding.md) refuses
  rounding, because the customer would be charged one figure and credited another.
- UGX at exponent 2 on the strength of the special-case paragraph: the zero-decimal list on the same page says the
  opposite, and a wrong exponent changes the charge by a factor of 100.

## Consequences

- An ISK amount with a fraction gets a 400 in both services, and every ISK amount charged, stored and credited is
  a whole number of krónur.
- A fractional ISK balance written before this rule stays in its wallet, and a transfer can move only its whole
  krónur. A payment event carrying a fractional ISK amount is refused as `INVALID_AMOUNT`
  ([0019](0019-payment-event-amounts-on-the-grid.md)); Stripe cannot produce one.
- UGX depends on Stripe's zero-decimal list being the correct half of its page. If Stripe confirms the other
  reading, UGX moves to `WHOLE_UNITS_IN_HUNDREDTHS` in both services.
