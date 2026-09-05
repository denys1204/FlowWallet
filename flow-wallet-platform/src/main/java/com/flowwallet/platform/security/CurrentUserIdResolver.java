package com.flowwallet.platform.security;

import com.flowwallet.platform.constant.HttpHeaders;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves controller method parameters annotated with {@link CurrentUserId}
 * by reading the {@code X-User-Id} HTTP header from the incoming request.
 * <p>
 * Throws {@link MissingUserIdException} if the header is absent, blank, or longer than a service can
 * store. Bounding the length here rather than at the database keeps the failure honest: services persist
 * the user id in a {@code VARCHAR}, and an over-long value that reaches an insert surfaces as whatever
 * constraint happens to fire, which callers then read as an unrelated error about their own request.
 */
public class CurrentUserIdResolver implements HandlerMethodArgumentResolver {
    /**
     * Longest user id any service will store. Kept in step with the {@code user_id} column width.
     */
    public static final int MAX_USER_ID_LENGTH = 64;

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

        return stripped;
    }
}
