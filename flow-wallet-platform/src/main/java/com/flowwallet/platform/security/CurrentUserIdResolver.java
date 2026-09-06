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
 * The UUID rule is a security property, not tidiness. Money is sent to a person by naming their id, and
 * there is deliberately no way to search for anyone — you know an id because it was shared with you. That
 * only holds while ids cannot be guessed, and nothing else in the system enforces it: the header is trusted
 * from an external service we do not control, so an identity space of e-mail addresses would quietly make
 * every user enumerable while everything kept working.
 * <p>
 * Only versions 4 and 7 pass, and that exclusion is the point rather than pedantry. Versions 3 and 5 are
 * deterministic hashes of a name in a namespace, so anyone who knows the namespace and someone's e-mail can
 * compute their id exactly; version 1 embeds a MAC address and a timestamp. All three are UUIDs by any naive
 * check and none of them is unguessable.
 * <p>
 * The rule is spelled out here rather than delegated to Hibernate Validator's {@code @UUID}, which is used
 * for the same job on request parameters. An argument resolver runs before bean validation and is
 * constructed by hand, so no annotation reaches it; the test suite pins this expression against the same
 * cases the annotation is configured for.
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
