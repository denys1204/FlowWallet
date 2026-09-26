/**
 * The published contract between services: Kafka event payloads, the topic that carries them and its header
 * names. The module has no dependencies and holds only what crosses the wire.
 * See docs/adr/0002-module-boundaries.md.
 * <p>
 * A topic always holds messages written by more than one version of the code, so changes here follow
 * evolution rules rather than whatever the compiler happens to accept:
 * <ul>
 *   <li>add optional fields only;</li>
 *   <li>never rename or remove a field — add the replacement, then drop the old one once
 *       every consumer has moved;</li>
 *   <li>never change a field's type;</li>
 *   <li>keep enums off the wire — an unknown constant fails deserialization on older consumers.</li>
 * </ul>
 * Every event opens with the envelope fields {@code eventId} and {@code schemaVersion}.
 * See docs/adr/0009-payment-event-contract.md.
 */
package com.flowwallet.contract;
