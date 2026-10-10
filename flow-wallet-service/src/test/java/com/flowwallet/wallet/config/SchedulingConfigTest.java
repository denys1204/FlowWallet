package com.flowwallet.wallet.config;

import com.flowwallet.outbox.OutboxAutoConfiguration;
import com.flowwallet.outbox.OutboxEventRepository;
import com.flowwallet.outbox.OutboxTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SchedulingConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void scheduledJobsRunUnlessTurnedOff() {
        // Guards the switch defaulting to off: a withdrawal command whose first send failed would never be retried,
        // and its money would stay debited with nothing on its way to Payment Service.
        runner.run(context -> assertThat(context)
                .hasBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
    }

    @Test
    void theSwitchTurnsEveryScheduledJobOff() {
        // Guards the switch a test relies on to drive the scheduled steps itself without a background run racing it.
        runner.withPropertyValues("wallet.scheduling.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
    }

    @Test
    void theOutboxJobsAreScheduled() {
        // Guards the outbox's poller, reaper and cleanup never running in the wallet: the fast path would hide it
        // until a failed send stayed PENDING for good.
        runner.withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
                .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(OutboxTopic.class, () -> new OutboxTopic("wallet.withdrawals"))
                .run(context -> assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks())
                        .extracting(task -> task.getTask().toString())
                        .anyMatch(task -> task.endsWith("OutboxPoller.pollOutbox"))
                        .anyMatch(task -> task.endsWith("OutboxPoller.reapStuckProcessing"))
                        .anyMatch(task -> task.endsWith("OutboxPoller.cleanupOldEvents")));
    }

    @Test
    void theShippedPoolHasAThreadForEveryScheduledJob() throws Exception {
        // Guards Boot's single scheduler thread coming back: the daily cleanup would then hold back the poller that
        // retries withdrawal commands. Three jobs run on it: the outbox poller, reaper and cleanup.
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(Integer.parseInt(String.valueOf(yaml.getProperty("spring.task.scheduling.pool.size"))))
                .isGreaterThanOrEqualTo(3);
    }
}
