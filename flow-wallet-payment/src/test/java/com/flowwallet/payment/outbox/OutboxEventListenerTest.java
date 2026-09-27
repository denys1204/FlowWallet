package com.flowwallet.payment.outbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxEventListenerTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(OutboxEventListener.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private OutboxMessageSender sender;
    private OutboxEventListener listener;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        sender = mock(OutboxMessageSender.class);
        listener = new OutboxEventListener(sender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void theFastPathSendsTheRowItWasToldAbout() {
        // A wrong id would leave every event to the poller and add its interval to every credit.
        listener.handleOutboxCreatedEvent(new OutboxCreatedEvent(7L));

        verify(sender).processEvent(7L);
    }

    @Test
    void aFailedAttemptIsLoggedOnceAsAWarningAndNotRethrown() {
        // The attempt is already recorded and the poller retries it; an ERROR with a stack trace, or a rethrow
        // that the async executor logs again, would report one broker hiccup as several failures.
        doThrow(new OutboxMessageProcessingException("Attempt 1 of 10 failed")).when(sender).processEvent(7L);

        assertThatCode(() -> listener.handleOutboxCreatedEvent(new OutboxCreatedEvent(7L))).doesNotThrowAnyException();

        assertThat(logs.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("Attempt 1 of 10 failed");
                    assertThat(e.getThrowableProxy()).isNull();
                });
    }

    @Test
    void anUnexpectedErrorIsLoggedOnceAsAnErrorWithItsStackTrace() {
        // A database error after a delivered send is not a failed attempt. The row waits for the reaper, so the
        // error must be visible, but rethrowing it would only make the async executor log it a second time.
        doThrow(new DataAccessResourceFailureException("db down")).when(sender).processEvent(7L);

        assertThatCode(() -> listener.handleOutboxCreatedEvent(new OutboxCreatedEvent(7L))).doesNotThrowAnyException();

        assertThat(logs.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(e.getThrowableProxy()).isNotNull();
                    assertThat(e.getThrowableProxy().getMessage()).isEqualTo("db down");
                });
    }
}
