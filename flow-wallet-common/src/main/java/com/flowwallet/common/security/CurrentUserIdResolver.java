package com.flowwallet.common.security;

import com.flowwallet.common.constant.HttpHeaders;
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
 * Throws {@link MissingUserIdException} if the header is absent or blank.
 */
public class CurrentUserIdResolver implements HandlerMethodArgumentResolver {
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

        return userId.strip();
    }
}
