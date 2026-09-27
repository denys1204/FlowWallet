# 0019. Payment event amounts sit on the wallet's grid, and a refusal stores only an amount its column holds

- Status: Accepted, extends [0010](0010-idempotent-payment-event-consumer.md) and
  [0015](0015-currency-precision-and-no-rounding.md)
- Date: 2026-09-27

## Context

In [0015](0015-currency-precision-and-no-rounding.md) every amount moved inside the wallet sits on
`AmountPrecision`'s grid: whole units for the zero-decimal currencies, two decimals for every other, at most 15
integer digits. Transfers are held to it through `AmountPrecision.canonical`. The consumer in
[0010](0010-idempotent-payment-event-consumer.md) checks a completed event's amount only for null, zero or negative,
and `Wallet.credit` takes the amount on trust. The event records carry no validation, so Payment Service's own checks
are the only thing between a malformed amount and a balance. An amount such as 10.123 USD or 10.5 JPY is credited as
units that no deposit or transfer can produce, and a balance holding them can never be paid out in full. An amount
finer than `NUMERIC(19,4)`, such as 0.00001, is rounded by Postgres to 0.0000 and refused by
`balance_history_amount_positive`. The consumer then treats the violation as neither barrier, so the record is
retried and dead-lettered instead of being stored as a refusal.

A refused event is stored in `processed_events`, whose `amount` column is also `NUMERIC(19,4)`. Postgres would
round a finer amount into a figure the event never carried, and would refuse one with more than 15 integer digits
with a numeric overflow, which the consumer also retries and dead-letters.

## Decision

- `PaymentEventListener.refusalFor` refuses a completed event as `INVALID_AMOUNT` when its amount is missing, zero,
  negative or off its currency's grid. The grid check is `AmountPrecision.isOnGrid`, which applies the same rules as
  `canonical` without throwing, judged after `stripTrailingZeros` and case-insensitive on the currency. The envelope
  is checked first, because the grid depends on the currency.
- `ProcessedEvent.rejected` and `ProcessedEvent.failureRecorded` store the event's amount only when
  `AmountPrecision.fitsLedger` says `NUMERIC(19,4)` holds it exactly (at most 15 integer digits and 4 decimals).
  Otherwise `amount` is NULL. The payload keeps the amount as it was sent.

## Alternatives considered

- Rounding an off-grid amount to the currency's scale before crediting it. The wallet would credit a figure other
  than the one the payment carried, which [0015](0015-currency-precision-and-no-rounding.md) rules out.
- Relying on `balance_history_amount_positive`. It catches only amounts that round to zero, and it catches them as a
  failure to retry and dead-letter rather than as a refusal to store.
- Letting a too-large amount fail its insert and go to the dead-letter topic. The wallet read the event and
  understood why it refuses it, and the dead-letter topic is for records it could not read or settle.
- Storing a rounded or capped amount in `processed_events`. Totals of refused money would add figures that no event
  carried.
- A second copy of the grid rules in the consumer. Two copies drift, and an event could then credit an amount a
  transfer of the same money refuses.

## Consequences

- Every amount a payment event credits is one a transfer could move. Money sent with an off-grid amount is stored as
  `REJECTED` with `INVALID_AMOUNT` and its payload, and only an operator can act on it.
- `processed_events.amount` is NULL for an event that carried no amount, and for one whose amount the column cannot
  hold exactly. Totals over the column leave those rows out, and the payload has the figure.
- `AmountPrecision` is the one place the grid is defined for the wallet, and `isOnGrid` and `canonical` must keep
  agreeing.
