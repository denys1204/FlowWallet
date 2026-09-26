/**
 * Servlet-side infrastructure shared by the services: error handling, the {@code @CurrentUserId} resolver,
 * header names and {@code @Iso4217Currency}.
 * <p>
 * Nothing here describes the business domain. Anything that does belongs to the service that owns it, or, if it
 * crosses the wire, to the contract module. See docs/adr/0002-module-boundaries.md.
 */
package com.flowwallet.platform;
