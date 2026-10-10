package com.flowwallet.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventRepositoryTest {
    private static final Pattern STATUS_LITERAL = Pattern.compile(
            "([\\w.]+)\\.(PENDING|PROCESSING|COMPLETED|FAILED)\\b"
    );

    @Test
    void everyStatusInAQueryNamesTheOutboxStatusEnum() {
        // Guards the fully qualified enum names in the queries: after a package move a stale name fails only when
        // Hibernate parses the queries at startup, which no unit test does.
        List<String> queries = Arrays.stream(OutboxEventRepository.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Query.class))
                .filter(Objects::nonNull)
                .map(Query::value)
                .toList();

        assertThat(queries).isNotEmpty();
        for (String query : queries) {
            Matcher literal = STATUS_LITERAL.matcher(query);
            assertThat(literal.find()).as(query).isTrue();
            do {
                assertThat(literal.group(1)).as(query).isEqualTo(OutboxStatus.class.getName());
            } while (literal.find());
        }
    }
}
