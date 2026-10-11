# 0045. A transfer reversed in full is ignored, and a transfer key that answers with one moves to the next generation

- Status: Accepted, extends [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md) and
  [0037](0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md), supersedes the consequence of
  [0037](0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md) that a `requeue` from `ACCEPTED` on
  `MULTIPLE` or `MISMATCH` meets the same cause
- Date: 2026-10-11

## Context

A payout waits from `ACCEPTED` on `MULTIPLE` or `MISMATCH` when its transfer group `wd-<reference>` holds a stray
transfer: two transfers, or one with other terms. An operator reverses the strays in full in the Dashboard, but the
transfer step counted every transfer of the group, reversed or not, so a `requeue` met the same cause, and the only exit
was to reverse everything and return the payout
([ADR 0037](0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md)). The withdrawal could not be paid out
any more.

Ignoring reversed transfers and creating again is not enough on its own. Stripe stores the first response under an
idempotency key and replays it to every retry for at least 24 hours, so a create under the key that made a transfer
since reversed in full answers with that transfer as it was created, `amount_reversed` 0. Adopting it would send the
payout on with no money in the connected account. A fresh key taken on what a listing shows is no better: a request
still running under the old key, after a timeout on this side, could land a second transfer beside the new one.

## Decision

- `ProviderTransfer.reversedInFull()`, reversals that reach the transfer's own amount, is the one rule for a transfer
  that holds no money. The transfer step and `return-without-reversal` both use it.
- On a later try, the transfer step counts and adopts only the transfers of the group not reversed in full. One with
  the payout's terms is adopted. More than one is `MULTIPLE`, and one with other terms is `MISMATCH`. A transfer
  reversed in part still holds money, so it is never adopted or ignored, and its `MISMATCH` detail says so.
- When the group holds no transfer that is not reversed in full, the step creates under its current key generation, as
  it does after any unknown outcome. If Stripe answers with a transfer that the same try's listing shows reversed in
  full, the answer is the key's replay of its own transfer: the key can make nothing more and nothing of the payout is
  out. The step records `KeySpent`, which raises the key generation, counts the try and leaves the row due at once with
  no event. When the bank account is no longer `READY`, it returns the payout with `BANK_ACCOUNT_NOT_SET` instead.
- The transfer step never moves its key generation on a listing alone, an unknown answer, a 409, a requeue or a
  hand-over. This adds a second definite answer to ADR 0035's rule that the generation rises only after a definite
  refusal the step retries.
- The step reads no transfer by id. Stripe's search documentation points read-after-write flows to the list APIs,
  which its search delays do not affect. Even a stale listing can only miss a reversal, never show one that did not
  happen, so it cannot make `KeySpent` fire for a transfer that still holds money.
- `requeue`, the quiet period, the operator checks, the set of attention causes and the schema stay as they are. A
  `MULTIPLE` detail names the transfers that still hold money.

## Alternatives considered

- Raising the key generation when a listing shows only reversed transfers: a request still running under the old key,
  or a transfer the listing missed, would sit beside the new key's transfer.
- A requeue under a fresh generation: ADR 0037 rejects it for the same reason.
- A fourth operator action that raises the generation after the quiet period: it rests on timing instead of the key's
  own answer, adds an action to the endpoint, and a plain requeue within 24 hours would still meet `MISMATCH`.
- Reading each transfer by id before adopting it, or each create's answer: it guards against a list lag that Stripe's
  documentation rules out, and a reversal after the read races the record just as one after the listing does.
- Stripe's `Idempotent-Replayed` header: it says an answer is a replay, not that the transfer is back.
- Keeping the return-only runbook: a withdrawal whose strays were reversed could never be paid out.

## Consequences

- An operator who keeps one transfer with the payout's terms and reverses the others in full, then requeues, gets the
  payout paid out from it. One who reverses every transfer and requeues gets a new transfer; to give the money back
  instead, `return-without-reversal` is the action.
- The key generation of a row in `ACCEPTED` can be above 0, so transfer, payout and reversal keys can end in `-1` or
  higher. The executor logs the change at WARN and counts it as `key_spent`.
- A reversal made in the Dashboard after the step's listing, on a transfer the step then adopts, still records
  `TRANSFERRED` for money that is back. The payout step is refused for funds and the payout ends `RETURNED` through
  `PAYOUT_FAILED`, unless the connected account holds other money the payout then spends. The runbook allows reversals
  only while the row waits, before its requeue.
- A key that stored a 500 keeps replaying it, so the row reaches `UNKNOWN_LIMIT` with its generation unchanged, as
  before.
- The return for a bank account that is no longer ready happens only on the try that sees the replay. If the account
  stops being ready before the next try, that try creates under the fresh key, and the payout step reverses the new
  transfer and returns the payout, as for any transfer made before the account changed.
