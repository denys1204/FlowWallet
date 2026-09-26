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
 * Resolves {@link CurrentUserId} parameters from the {@code X-User-Id} header. Throws
 * {@link MissingUserIdException} if the header is absent, blank, longer than {@link #MAX_USER_ID_LENGTH} or not a
 * random-based UUID, and returns a valid value stripped and lower-cased, so one identity has one spelling.
 * <p>
 * The UUID check is a hand-written expression because an argument resolver runs before bean validation and is
 * constructed by hand, so no constraint annotation reaches it.
 * See docs/adr/0003-caller-identity-and-trust-boundary.md.
 */
public class CurrentUserIdResolver implements HandlerMethodArgumentResolver {
    /**
     * Longest user id any service stores, kept in step with the {@code VARCHAR(64)} user id columns. A UUID never
     * reaches it. The bound guards storage, not identity, so it stays if the identity rule is relaxed.
     */
    public static final int MAX_USER_ID_LENGTH = 64;

    /**
     * A random-based UUID: version 4 or 7, RFC 4122 variant, ASCII hex in either case. Versions 1, 3 and 5 are
     * refused because they derive from a MAC address or a knowable name.
     * <p>
     * The wallet's {@code TransferRequest} checks a transfer's recipient with this expression through
     * {@code @Pattern}, so a change here changes that rule too.
     */
    public static final String RANDOM_UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[47][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

    private static final Pattern RANDOM_UUID = Pattern.compile(RANDOM_UUID_REGEX);

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

        // Neither message below echoes the value: the detail goes back to the caller and into the log.
        String stripped = userId.strip();
        if (stripped.length() > MAX_USER_ID_LENGTH) {
            throw new MissingUserIdException(
                    "%s must be at most %d characters, got %d"
                            .formatted(HttpHeaders.USER_ID, MAX_USER_ID_LENGTH, stripped.length())
            );
        }

        if (!RANDOM_UUID.matcher(stripped).matches()) {
            throw new MissingUserIdException(
                    HttpHeaders.USER_ID + " must be a random-based UUID (version 4 or 7)"
            );
        }

        return stripped.toLowerCase(Locale.ROOT);
    }
}
