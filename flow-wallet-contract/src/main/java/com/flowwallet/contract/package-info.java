/**
 * The published contract between services: Kafka event payloads and the topic names
 * that carry them. This module has no dependencies on purpose — a contract that drags
 * a framework along stops being a contract.
 * <p>
 * Producer and consumer are deployed separately, so a topic always holds messages written
 * by more than one version of the code. Changes here follow evolution rules rather than
 * whatever the compiler happens to accept:
 * <p>
 * Every event opens with two envelope fields. {@code eventId} identifies the message itself and is
 * what a consumer deduplicates on — {@code transactionReference} cannot serve that purpose, because
 * one payment may legitimately emit a failure and then a success. {@code schemaVersion} says which
 * shape the rest follows.
 * <ul>
 *   <li>add optional fields only;</li>
 *   <li>never rename or remove a field — add the replacement, then drop the old one once
 *       every consumer has moved;</li>
 *   <li>never change a field's type;</li>
 *   <li>keep enums off the wire — an unknown constant fails deserialization on older consumers.</li>
 * </ul>
 */
package com.flowwallet.contract;
