package com.flowwallet.platform.security;

import com.flowwallet.platform.constant.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserIdResolverTest {
    private final CurrentUserIdResolver resolver = new CurrentUserIdResolver();

    private static final String V4 = "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d";

    private String resolve(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader(HttpHeaders.USER_ID, headerValue);
        }
        return resolver.resolveArgument(null, null, new ServletWebRequest(request), null);
    }

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(resolve("  " + V4 + "  ")).isEqualTo(V4);
    }

    @Test
    void rejectsAMissingHeader() {
        assertThatThrownBy(() -> resolve(null)).isInstanceOf(MissingUserIdException.class);
    }

    @Test
    void rejectsABlankHeader() {
        assertThatThrownBy(() -> resolve("   ")).isInstanceOf(MissingUserIdException.class);
    }

    @ParameterizedTest(name = "{1} is accepted")
    @CsvSource({
            "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d, version 4 (random)",
            "018f3a2b-7c4d-7e5f-8a9b-0c1d2e3f4a5b, version 7 (time-ordered with 62 random bits)",
    })
    void acceptsARandomBasedUuid(String identity, String description) {
        assertThat(resolve(identity)).isEqualTo(identity);
    }

    @Test
    void normalisesCaseSoOneIdentityHasOneSpelling() {
        // Refusing a valid identity over capitalisation would be a needless outage, but two spellings of one
        // identity would be two users -- two wallets, two balances. So it is accepted and folded.
        assertThat(resolve(V4.toUpperCase())).isEqualTo(V4);
    }

    @ParameterizedTest(name = "{1} is refused")
    @CsvSource({
            "3f2504e0-4f89-11d3-9a0c-0305e82c3301, version 1 embeds a MAC address and a timestamp",
            "886313e1-3b8a-5372-9b90-0c9aee199e5d, version 5 is a hash of a name and is computable",
            "00000000-0000-0000-0000-000000000000, the nil UUID",
    })
    void refusesIdentitiesThatCanBeGuessed(String identity, String why) {
        // The whole transfer design rests on identities being unguessable: money is sent by naming an id and
        // there is no way to search for anyone. A version-5 id can be computed by whoever knows the
        // namespace and the person's e-mail, which passes any naive "is it a UUID" check and defeats it.
        assertThatThrownBy(() -> resolve(identity))
                .isInstanceOf(MissingUserIdException.class)
                .hasMessageContaining("UUID");
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {
            "alice",
            "denis@example.com",
            "1-1-1-1-1",                             // UUID.fromString accepts this and invents a UUID
            "4c9a1b2e1f3d4a5b8c7d9e0f1a2b3c4d",      // no dashes
            "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4",   // one digit short
    })
    void refusesAnythingThatIsNotAUuid(String identity) {
        assertThatThrownBy(() -> resolve(identity)).isInstanceOf(MissingUserIdException.class);
    }

    @Test
    void refusesAnIdentityTooLongToStore() {
        assertThatThrownBy(() -> resolve("a".repeat(CurrentUserIdResolver.MAX_USER_ID_LENGTH + 1)))
                .isInstanceOf(MissingUserIdException.class)
                .hasMessageContaining("at most");
    }
}
