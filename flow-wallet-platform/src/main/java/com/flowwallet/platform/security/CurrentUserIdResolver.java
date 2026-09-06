package com.flowwallet.platform.security;

import com.flowwallet.platform.constant.HttpHeaders;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resolves controller method parameters annotated with {@link CurrentUserId}
 * by reading the {@code X-User-Id} HTTP header from the incoming request.
 * <p>
 * Throws {@link MissingUserIdException} if the header is absent, blank, longer than a service can store,
 * or not a random-based UUID.
 * <p>
 * The rule is short to state: <strong>an identity must be opaque and must not be derived from anything
 * knowable.</strong> That is data hygiene rather than a defence against an attacker with a word list — a
 * version-4 UUID carries 122 random bits, so its space cannot be searched, and a hit would reveal only that
 * some number is registered. There is no name, e-mail or profile behind it to leak.
 * <p>
 * What the excluded versions actually cost is narrower and real. Versions 3 and 5 are deterministic hashes
 * of a name in a namespace, so they let someone <em>confirm a specific guess</em>: suspecting an id is
 * {@code uuid5(ns, "someone@example.com")}, you compute it and compare. That is not searching a space, it is
 * checking one hypothesis, and no amount of entropy prevents it. Version 1 embeds a MAC address and a
 * creation time, which is a small leak with nothing to show for it. All three are UUIDs by any naive check.
 * <p>
 * Today the rule carries more weight than hygiene, because {@code X-User-Id} is not authenticated: whoever
 * writes the header is that user. Once the gateway validates a token and services are unreachable except
 * through it, knowing an id stops being worth anything and this drops back to hygiene. It stays either way —
 * it costs one expression, and the day the identity scheme changes is a day worth hearing about.
 * <p>
 * The rule is spelled out here rather than delegated to Hibernate Validator's {@code @UUID}, which does the
 * same job on request parameters. An argument resolver runs before bean validation and is constructed by
 * hand, so no annotation reaches it; the tests pin this expression against the cases the annotation is
 * configured for.
 */
public class CurrentUserIdResolver implements HandlerMethodArgumentResolver {
    /**
     * Longest user id any service will store. Kept in step with the {@code user_id} column width.
     * <p>
     * A UUID is always well inside this, so today the bound cannot fire. It stays because it guards the
     * column, which is a different thing from what the pattern below guards: relaxing the identity rule is a
     * product decision, and the storage guard should not disappear as a side effect of one.
     */
    public static final int MAX_USER_ID_LENGTH = 64;

    /**
     * A random-based UUID: version nibble 4 or 7, RFC 4122 variant, either letter case. Case is accepted
     * either way and normalised below, because rejecting a valid identity over capitalisation would be a
     * needless outage.
     */
    private static final Pattern RANDOM_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[47][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    );

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && String.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public String resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new MissingUserIdException("Unable to access HttpServletRequest");
        }

        String userId = request.getHeader(HttpHeaders.USER_ID);
        if (userId == null || userId.isBlank()) {
            throw new MissingUserIdException("Missing required header: " + HttpHeaders.USER_ID);
        }

        String stripped = userId.strip();
        if (stripped.length() > MAX_USER_ID_LENGTH) {
            // The value itself is not echoed: it is rendered to the caller and logged.
            throw new MissingUserIdException(
                    "%s must be at most %d characters, got %d"
                            .formatted(HttpHeaders.USER_ID, MAX_USER_ID_LENGTH, stripped.length())
            );
        }

        if (!RANDOM_UUID.matcher(stripped).matches()) {
            // The value is not echoed: it is rendered to the caller and written to logs.
            throw new MissingUserIdException(
                    HttpHeaders.USER_ID + " must be a random-based UUID (version 4 or 7)"
            );
        }

        return stripped.toLowerCase(Locale.ROOT);
    }
}
