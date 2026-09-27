# 0015. Amounts sit on an explicit per-currency grid and are refused, never rounded

- Status: Accepted, extended by [0019](0019-payment-event-amounts-on-the-grid.md) and
  [0022](0022-stripe-charge-rules-checked-before-the-reservation.md)
- Date: 2026-09-05

## Context

Both services store amounts as `NUMERIC(19,4)`, and Postgres rounds a value finer than a column's scale instead of
refusing it. Stripe charges an integer in its own minor unit for each currency, and that unit is not always ISO 4217's:
Stripe treats MGA as zero-decimal where ISO gives two decimals, expects two decimals for ISK where ISO gives none, and
charges five currencies in thousandths while requiring their minor amounts to end in zero. Each currency therefore
yields two numbers: how many decimals an amount may carry, and the power of ten that converts it for Stripe. Deciding
the exponent alone lets an over-precise amount be rounded on the way out, so the customer is charged one figure and
credited another. Deciding the accepted scale alone lets an amount of the right precision go out with the wrong
exponent. Transfers move money inside Wallet Service and never reach Stripe, yet must stay on the grid deposits arrive
on. Clients write currency codes in either case, and `Currency.getInstance` accepts only upper case.

## Decision

- Currency codes are case-insensitive at the edge and upper-case in storage. `Currencies.normalise` upper-cases the
  path or body value, then validates it with `Currency.getInstance`, and throws `InvalidCurrencyException` (400) for a
  blank or unknown code. Validating first would refuse `usd` as "Not an ISO 4217 currency code", and without the
  upper-casing `/api/wallets/usd` would miss the `USD` wallet, since the `wallets_currency_is_upper` CHECK allows only
  upper case in storage.
- `@Iso4217Currency` checks a value against the JDK's currency table, lets `null` through so that it pairs with
  `@NotBlank`, and is case-sensitive. Payment Service uses it on `CreatePaymentIntentRequest.currency`, which the
  wallet always fills in upper case. `CreateWalletRequest` does not use it.
- `StripeCurrencyRules` is the authority for both numbers, and `java.util.Currency` is never consulted for them.
  `StripeCurrencyRules.of` returns a `CurrencyRule(acceptedScale, transmitExponent)`. The 16 codes in `ZERO_DECIMAL`
  have exponent 0, the five in `THREE_DECIMAL` (BHD, JOD, KWD, OMR, TND, the ones Stripe documents) have exponent 3,
  and every other code has exponent 2. The accepted scale is the exponent capped at `MAX_ACCEPTED_SCALE` (2), so a
  three-decimal amount always converts to a minor amount ending in zero.
- `StripeRequestMapper.validate` throws `InvalidPaymentRequestException` (400) when an amount carries more decimals than
  its currency accepts. It runs before the payment row is reserved ([0013](0013-deposit-initiation.md)).
  `toSmallestCurrencyUnit` then converts with `movePointRight(transmitExponent)` and `longValueExact()`, with no
  rounding mode. Validation has already refused finer amounts, so the shift is exact and `longValueExact` can fail only
  on an amount too large for a `long`.
- Transfers go through `AmountPrecision.canonical`, which holds a copy of the grid: whole units for its own
  `ZERO_DECIMAL` list, two decimals for every other currency. A transfer that created finer units would leave a balance
  that could never be paid out in full. The two `ZERO_DECIMAL` lists point at each other and change together. Why the
  grid is copied rather than shared is in [0002](0002-module-boundaries.md).
- An amount has at most 15 integer digits, the room `NUMERIC(19,4)` leaves. `canonical` checks size first, so an amount
  such as `1E+999999999` is refused before `setScale` could expand it, and it subtracts scale from precision in `long`
  so that an extreme scale cannot overflow. `PaymentDepositProperties` holds `max-amount` to the same room with
  `@Digits(integer = 15, fraction = 4)`, and startup fails otherwise.
- An amount that is off the grid or too large is refused with `InvalidAmountException.tooPrecise` or `tooLarge` (400),
  never rounded. `canonical` returns the value at scale 4 with `RoundingMode.UNNECESSARY`, which throws instead of
  rounding if the checks ever stop making the rescale exact.
- Both services judge an amount after `stripTrailingZeros`, so `10.5000` USD and `100.00` JPY are accepted. Precision
  is checked in code and not with `@Digits`.

## Alternatives considered

- Exponents from `java.util.Currency`: MGA would be charged a hundred times over and ISK a hundredth, and a JDK update
  revising an ISO figure could change a charge. In the wallet, MGA would allow decimals no deposit can produce, and a
  deposited `10.50` ISK could never move its `0.50`.
- Every ISO three-decimal currency, IQD and LYD included: it states an ISO fact as a Stripe fact. Both are charged at
  exponent 2.
- Accepting three decimals for BHD, JOD, KWD, OMR and TND, plus a rule that the minor amount ends in zero: a second rule
  to keep in step with the first.
- `HALF_UP` rounding when converting to minor units, which the converter once did: `100.005` was charged as `100.01`
  and credited as `100.005`.
- Letting Postgres round a value finer than `NUMERIC(19,4)`: `0.00001` would be stored as a movement of nothing.
- A shared dependency, or the list in `flow-wallet-platform` or `flow-wallet-contract`: ruled out by
  [0002](0002-module-boundaries.md).
- Routing transfers through Payment Service: it knows nothing about wallets, and a transfer is one local transaction
  in `wallet_db` ([0014](0014-transfers-in-one-local-transaction.md)).
- `@Digits(fraction = 2)` on amounts: Hibernate Validator counts a `BigDecimal`'s trailing zeros and would refuse
  `25.100`, and an annotation cannot see the currency, which on a transfer only the path carries.
- No size bound: an absurd transfer would get a 422 "Insufficient funds" about an amount that is wrong in itself, and a
  deposit limit past 15 digits would pass validation and then fail on insert with a 500.
- `@Iso4217Currency` on the create-wallet body: it would refuse `usd` in the body while the path accepts it.
- A length check, which lets `ABC` through, or a hand-kept list of ISO codes, which drifts from the runtime's table.

## Consequences

- The amount charged, stored, credited and returned is the amount the client asked for, or the request gets a 400
  that names the limit.
- A change to Stripe's zero-decimal list is made in `StripeCurrencyRules` and `AmountPrecision` together, and the
  compiler does not catch a missed side. The three-decimal list lives only in `StripeCurrencyRules`, since those
  currencies accept two decimals in both services.
- The set of valid codes follows the JDK's table, and the precision rules do not change with it. A currency Stripe
  treats differently from ISO needs an explicit table entry.
- Only the wallet's edge accepts lower case. Payment Service requires an upper-case code.
- A transfer amount is stored, compared and returned at scale 4, so a response prints the figure a replay reads back.
