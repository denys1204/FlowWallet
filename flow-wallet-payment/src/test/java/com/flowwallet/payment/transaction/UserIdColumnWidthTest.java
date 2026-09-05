package com.flowwallet.payment.transaction;

import com.flowwallet.platform.security.CurrentUserIdResolver;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resolver refuses a user id longer than the column can hold, and ddl-auto: validate ties the column to
 * the entity. This assertion closes the remaining link, so the three stay in step: widen one and this fails
 * rather than a caller receiving a message about a duplicate reference they never sent twice.
 */
class UserIdColumnWidthTest {
    @Test
    void userIdColumnMatchesTheResolversBound() throws Exception {
        Column column = PaymentTransaction.class.getDeclaredField("userId").getAnnotation(Column.class);

        assertThat(column.length()).isEqualTo(CurrentUserIdResolver.MAX_USER_ID_LENGTH);
    }
}
