# 0001. Record architecture decisions

- Status: Accepted
- Date: 2026-09-27

## Context

Most of FlowWallet's design reasoning sits in Javadoc. A convention in CLAUDE.md says comments and Javadoc explain
why, including the alternative that was rejected, and the code applies it at every site a decision touches. The
Javadoc on `TransferHandler.execute` argues the lock order, the point where the key is judged, why funds come before
the recipient and the order the ledger legs are written, each with the options not taken. `WalletRepository`,
`TransferBusyException`, CLAUDE.md and README.md explain the lock order again. Seven classes, CLAUDE.md and README.md
each explain that a constraint violation aborts a Postgres transaction.

The copies drift apart. Three classes in `flow-wallet-platform` call their module `flow-wallet-common`, a name the
build does not contain. `Wallet` justifies its `(user_id, currency)` constraint with two concurrent first payments,
but only `WalletService.open` creates a wallet, and the consumer refuses an event for a missing one with
`UnknownWalletException`. Long rationale also buries what a reader needs at the spot. `PaymentTransactionStore.reserve`
has a real local trap (every other constraint on the row is checked before the insert, so a new unchecked constraint
would be reported as a duplicate reference), and its Javadoc restates the Postgres abort rule beside it.

## Decision

A decision that spans more than one file is recorded once, as `docs/adr/NNNN-slug.md`, in Michael Nygard's form. The
file has a `# NNNN. Title` heading whose title states the decision, a Status line, a Date line, and the sections
Context, Decision, Alternatives considered (a short list) and Consequences. The Date is the date of the commit that
introduced the decision; if several commits built it up, it is the date of the first. An ADR names classes, methods,
tables and constraints, never line numbers.

Numbers have four digits, are assigned in order and are never reused. `docs/adr/README.md` is the index and lists
each ADR's number, title and status. An accepted ADR is not rewritten when the decision changes. A new ADR supersedes
it, and the status lines of both carry the link. A factual correction that leaves the decision unchanged, such as a
renamed class, is edited in place.

Each kind of text has one home:

- An ADR holds a cross-file decision with its context, rejected alternatives and consequences.
- A code comment holds only what a reader at that exact spot cannot get from the code and needs in order to change
  it safely: a local invariant, a trap, a non-obvious ordering. It takes a few lines and ends with
  `See docs/adr/NNNN-slug.md.` when the wider reasoning lives in an ADR. Rejected alternatives go in the ADR.
  Comments that restate the code are deleted. Public Javadoc keeps `@param`, `@return` and `@throws` only where they
  tell the caller something the signature does not.
- README.md describes behaviour for users and operators, and links the relevant ADR instead of arguing the decision.
- CLAUDE.md holds commands, conventions, framework traps, and each invariant as a one-line rule with an ADR pointer.

Javadoc always uses the multi-line form, never a one-line `/** ... */`. Lines stay within 120 columns. Comments and
ADRs use plain present-tense English, with no ticket numbers and no wording tied to a diff ("now", "new",
"this change").

This rule replaces the CLAUDE.md convention that comments and Javadoc explain why, including the rejected
alternative. The rule on test names and test comments is unchanged.

## Alternatives considered

- Rationale and rejected alternatives in Javadoc at every site. One rule ends up in as many as nine places, and the
  copies drift, as the `flow-wallet-common` name and the "two concurrent first payments" example show.
- One `architecture.md` holding every decision. The file keeps growing, has no status per decision and no way to
  supersede a single decision, and gives a code pointer no stable target.
- The reasoning in README.md. The document users read would mix current behaviour with history and rejected options.
- The reasoning in `implementation_plan.md`. It is local-only and git-ignored, so no other reader has it.
- A wiki or another external tool. It sits outside code review and drifts from the code.

## Consequences

- Each decision has one place to read and one place to change, and a rule stated once cannot contradict a copy of
  itself.
- Comments get shorter. A reader still finds at the spot what a safe change there needs, and follows the pointer for
  the reasoning.
- An ADR can drift from the code just as a comment can. Because it names classes, methods and constraints, a search
  for a renamed identifier still finds it. A change that alters a decision brings a superseding ADR in the same piece
  of work.
- Superseded ADRs stay in `docs/adr/`, and the index shows which decision is current.
- ADRs written for decisions already in the code take their Date from the history (`git log -S`, `git log --follow`),
  not from the day the ADR is written.
- This ADR decides no system behaviour.
