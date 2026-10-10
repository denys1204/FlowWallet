package com.flowwallet.wallet.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on every scheduled job in the service: the outbox poller, reaper and cleanup.
 * {@code wallet.scheduling.enabled=false} turns them all off, so a test that drives those steps itself is not raced
 * by a background run. See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "wallet.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
