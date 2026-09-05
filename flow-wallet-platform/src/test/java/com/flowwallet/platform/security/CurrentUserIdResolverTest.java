package com.flowwallet.platform.security;

import com.flowwallet.platform.constant.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserIdResolverTest {
    private final CurrentUserIdResolver resolver = new CurrentUserIdResolver();

    private String resolve(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader(HttpHeaders.USER_ID, headerValue);
        }
        return resolver.resolveArgument(null, null, new ServletWebRequest(request), null);
    }

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(resolve("  alice  ")).isEqualTo("alice");
    }

    @Test
    void rejectsAMissingHeader() {
        assertThatThrownBy(() -> resolve(null)).isInstanceOf(MissingUserIdException.class);
    }

    @Test
    void rejectsABlankHeader() {
        assertThatThrownBy(() -> resolve("   ")).isInstanceOf(MissingUserIdException.class);
    }

    @Test
    void acceptsAnIdentityAtTheLimit() {
        String atLimit = "a".repeat(CurrentUserIdResolver.MAX_USER_ID_LENGTH);

        assertThat(resolve(atLimit)).hasSize(CurrentUserIdResolver.MAX_USER_ID_LENGTH);
    }

    @Test
    void rejectsAnIdentityTooLongToStore() {
        // Without this bound the value reaches an insert and violates the user_id column, which the store
        // reports as a duplicate transaction reference -- an answer about a field the caller got right.
        String tooLong = "a".repeat(CurrentUserIdResolver.MAX_USER_ID_LENGTH + 1);

        assertThatThrownBy(() -> resolve(tooLong))
                .isInstanceOf(MissingUserIdException.class)
                .hasMessageContaining("at most")
                .hasMessageNotContaining(tooLong);
    }
}
