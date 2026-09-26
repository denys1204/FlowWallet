package com.flowwallet.platform.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a controller's {@code String} parameter to the caller's id from the {@code X-User-Id} header, validated and
 * lower-cased. An unusable header answers 401 before the method runs.
 * <p>
 * Nothing authenticates the header: the gateway forwards the client's value unchanged.
 * See docs/adr/0003-caller-identity-and-trust-boundary.md.
 *
 * @see CurrentUserIdResolver
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {}
