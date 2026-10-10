/**
 * The transactional outbox the services share: the {@code outbox_events} row, the claim rules of its sends, the
 * fast path after the commit, the poller with its reaper and cleanup, and the {@code outbox} actuator endpoint.
 * <p>
 * A service writes through {@link com.flowwallet.outbox.Outbox#enqueue}, declares its
 * {@link com.flowwallet.outbox.OutboxTopic} and owns the {@code outbox_events} table in its own schema. Nothing here
 * knows what an event means. See docs/adr/0008-transactional-outbox.md,
 * docs/adr/0018-outbox-sends-own-their-claim.md and docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
package com.flowwallet.outbox;
