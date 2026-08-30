/**
 * Cross-cutting infrastructure shared by every service: error handling,
 * the {@code @CurrentUserId} resolver and transport-level constants.
 * <p>
 * Nothing here describes the business domain. Anything that does belongs to the
 * service that owns it, or — if it crosses a service boundary — to the contract module.
 */
package com.flowwallet.platform;
