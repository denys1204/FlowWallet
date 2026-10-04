package com.flowwallet.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void scheduledJobsRunUnlessTurnedOff() {
        // Guards the switch defaulting to off: a send that failed right after its commit would never be retried,
        // a stuck outbox row never reaped, and a lost webhook never recovered.
        runner.run(context -> assertThat(context)
                .hasBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
    }

    @Test
    void theSwitchTurnsEveryScheduledJobOff() {
        // Guards the switch a test relies on to drive the scheduled steps itself without a background run racing it.
        runner.withPropertyValues("payment.scheduling.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
    }

    @Test
    void theShippedPoolHasAThreadForEveryScheduledJob() throws Exception {
        // Guards Boot's single scheduler thread coming back: a reconciler run of sequential Stripe calls would then
        // hold back the outbox poller that delivers payment events. Four jobs run on it: the outbox poller, reaper
        // and cleanup, and the reconciler.
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(Integer.parseInt(String.valueOf(yaml.getProperty("spring.task.scheduling.pool.size"))))
                .isGreaterThanOrEqualTo(4);
    }
}
