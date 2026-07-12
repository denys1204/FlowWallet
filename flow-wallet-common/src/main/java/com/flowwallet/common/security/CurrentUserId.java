package com.flowwallet.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Resolves the current user's ID from the {@code X-User-Id} HTTP header.
 * <p>
 * Usage in controllers:
 * <pre>{@code
 * @PostMapping("/api/wallets")
 * public WalletResponse create(@CurrentUserId String userId, @RequestBody CreateWalletRequest request) {
 *     // userId is resolved from the X-User-Id header
 * }
 * }</pre>
 * <p>
 * In production, API Gateway would extract the user ID from a validated JWT
 * and set the {@code X-User-Id} header. For the showcase, clients pass it directly.
 *
 * @see CurrentUserIdResolver
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {}
