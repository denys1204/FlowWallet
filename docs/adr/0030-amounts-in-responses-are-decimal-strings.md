# 0030. Amounts in responses are decimal strings at the currency's scale

- Status: Accepted, extends [0015](0015-currency-precision-and-no-rounding.md), supersedes the response scale of
  [0014](0014-transfers-in-one-local-transaction.md) and [0015](0015-currency-precision-and-no-rounding.md)
- Date: 2026-10-04

## Context

The wallet's responses carried money as JSON numbers at the ledger's scale. `WalletMapper` copied the `BigDecimal`
read from a `NUMERIC(19,4)` column, so a balance of 75 USD went out as `75.0000` and one of 1000 JPY as `1000.0000`.
`TransferResponse` held the same scale on purpose, so that a first answer and a replay printed alike
([0014](0014-transfers-in-one-local-transaction.md), [0015](0015-currency-precision-and-no-rounding.md)).

A JSON number is read as a binary floating-point value by JavaScript and by many JSON libraries. A double holds about 16
significant digits, and a wallet amount can have 17 (15 integer digits and two decimals), so a client can silently
change the figure it shows or sends back. The four decimals also say nothing about the currency: a client cannot tell
from `1000.0000` that yen have no minor unit. Once the API is published, changing the type or the scale of these fields
breaks every client.

## Decision

- Every amount in a wallet response is a JSON string holding the plain decimal value at its currency's scale:
  `"75.00"` for USD, `"1000"` for JPY. `AmountPrecision.render` writes it, at the scale `AmountPrecision.decimals`
  returns: 0 for the zero-decimal currencies and ISK, 2 for every other, including KWD and BHD, which the grid caps
  at two ([0015](0015-currency-precision-and-no-rounding.md), [0023](0023-isk-charged-in-whole-units.md)). A stored
  value finer than the grid, which only a row written before its currency's grid can hold, keeps its digits rather
  than being rounded.
- `WalletResponse` carries `decimals`, so a client can format and check an amount without a currency table of its
  own.
- `WalletResponse.of`, `BalanceHistoryResponse.of` and `TransferResponse.of` build the responses and call `render`.
  A first answer built in memory and a replay read from the ledger therefore print alike whatever scale each
  value holds. `WalletMapper` is removed.
- `ResponseAmountsTest` finds every record named `*Response` in the wallet and fails if one has a `BigDecimal`,
  `double` or `float` component.
- A request takes an amount as a string or as a JSON number. The string is the documented form, because it is the
  form a client reads back.
- The Kafka events and the internal Payment Service call keep their JSON numbers. The contract does not retype a
  field in place ([0009](0009-payment-event-contract.md)), and neither is part of the public API.

## Alternatives considered

- JSON numbers at the currency's scale: a smaller change, but a client still parses them as floats, and many JSON
  libraries drop the trailing zeros that carry the scale.
- Integer minor units, as Stripe takes them (`2500` for 25.00 USD): requests take major units, and ISK, which Stripe
  writes in hundredths but charges in whole units, would leak into the public API.
- Keeping MapStruct with a conversion method chosen by type: the mapper falls back to `BigDecimal.toString`, at the
  ledger's scale, if the method stops matching, and the build ignores unmapped fields, so nothing fails.
- The scale of `java.util.Currency`: it disagrees with the grid for MGA (two decimals against none) and for the
  three-decimal currencies such as KWD and BHD (three against two), so a client would format amounts the wallet
  refuses.

## Consequences

- The type of every money field changes from number to string, and the scale drops from four places to the
  currency's. This breaks a client written against the earlier responses, so it is made while the API has no
  published clients.
- A response added later, such as a withdrawal receipt, renders its money through `render`. If it holds money as a
  `BigDecimal`, `double` or `float`, `ResponseAmountsTest` fails.
- The ledger keeps storing and comparing amounts at scale 4; only the rendering changes.
