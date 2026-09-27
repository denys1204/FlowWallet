# 0021. Each wallet numbers its ledger entries under its row lock, and history is ordered and paged by that number

- Status: Accepted, supersedes the history paging of [0012](0012-balances-and-append-only-ledger.md)
- Date: 2026-09-27

## Context

In [0012](0012-balances-and-append-only-ledger.md) a wallet's history is paged by `balance_history.id`: the
query orders by id descending and the cursor `before` is an id. Ids come from `balance_history_seq`
through Hibernate's pooled optimizer with `allocationSize = 50`, so each JVM takes a block of 50 values and hands
them out locally. Two Wallet Service instances, or an old and a new one during a rolling update, hold different
blocks, and a movement committed later can carry a lower id than one committed earlier. The history then comes back
out of commit order, its top row's `balanceAfter` is not the wallet's balance, and a client that pages from the
newest entry it knows misses movements, while `docs/api.md` promises that the ledger grows only at its newest end.

Every movement on a wallet is written while its row lock is held ([0011](0011-wallet-row-locking.md)), so the
writers of one wallet already run one after another.

## Decision

- `wallets.last_entry_no` holds the entry number of the wallet's newest ledger row, 0 before the first.
  `Wallet.credit` and `Wallet.debit` advance it by one together with the balance, under the row lock the caller
  holds. A refused debit leaves it unchanged.
- `balance_history.entry_no` is the movement's place in its wallet's ledger. The factories
  `BalanceHistory.deposit`, `transferOut` and `transferIn` take it from the wallet's counter right after the
  mutation they record, so a deposit and both legs of a transfer are numbered by the wallet they belong to.
  Because the counter commits or rolls back with the rows, each wallet's entries are numbered 1, 2, 3 in commit
  order.
- `balance_history_wallet_entry_no_key` makes `(wallet_id, entry_no)` unique. A writer that numbered a row from a
  stale counter fails at the flush instead of storing two rows under one number. The index leads with
  `wallet_id`, so it replaces `idx_balance_history_wallet_id`.
- History is ordered by `entry_no` descending and paged by it. `GET /api/wallets/{currency}/history` takes
  `before`, the entry number of the oldest movement already seen, and returns it as `nextBefore`. The first page
  (`BalanceHistoryRepository.findNewest`) and every later one (`findPageBefore`) are separate queries, so the
  bound is never an optional parameter the planner cannot use as an index range.
- `BalanceHistoryResponse` carries `entryNo` and no row id. The id orders nothing, no endpoint accepts it, and
  beside `entryNo` it would invite a client to pass the wrong number as the cursor.
- Ids stay on the pooled sequence with `allocationSize = 50`, like every other table. They identify rows and
  nothing else.
- Migration `010-add-ledger-entry-numbers` numbers the existing rows with
  `ROW_NUMBER() OVER (PARTITION BY wallet_id ORDER BY id)` and sets each counter to its wallet's last number.
  Every row before it was written by a single instance, so id order is commit order for them. It runs in one
  transaction that first locks `wallets` and `balance_history` in EXCLUSIVE mode.

## Alternatives considered

- `allocationSize = 1`. Every insert would take a sequence round trip, and sequence values still do not follow
  commit order: two transactions can take values in one order and commit in the other.
- Ordering by `created_at`. Each instance stamps it from its own clock before the commit, and two rows can share
  a value, so it neither orders nor pages reliably.
- `MAX(entry_no) + 1` read from `balance_history` at insert time. It costs a query per movement, while the
  counter sits on the wallet row the writer has already locked and loaded.
- One global ledger sequence number assigned at commit. It needs a table-wide lock or a serialized sequence,
  while order only has to hold within one wallet.
- Keeping `id` in the response as well. It serves no request, and a client holding both numbers can page with
  the wrong one and get a plausible but wrong page.

## Consequences

- A wallet's history is in commit order however many instances write it, the newest entry's `balanceAfter` is the
  wallet's balance, and paging from any entry number never skips or repeats a movement.
- Every balance change must go through `Wallet.credit` or `Wallet.debit` and then a `BalanceHistory` factory for
  that wallet, in the same transaction. A new kind of movement, such as a withdrawal, gets its number the same
  way.
- A page reads about `limit` rows from the unique index, however long the history is.
- A cursor a client kept from the id paging of [0012](0012-balances-and-append-only-ledger.md) is an id, not an
  entry number, and pages the wrong rows.
- The migration's lock waits for running writers to finish and blocks balance writes until it commits. An instance
  of the previous version still running afterwards inserts rows without `entry_no` and fails on NOT NULL: its
  credits are retried and then dead-lettered, and its transfers answer 500. A deployment therefore stops the
  previous version before the first instance of this one starts.
