package com.flowwallet.outbox;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OutboxAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
            .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    private final ApplicationContextRunner withTopic = runner
            .withBean(OutboxTopic.class, () -> new OutboxTopic("test.events"));

    @Test
    void aServiceThatDeclaresItsTopicGetsTheWholeOutbox() {
        // Guards a service that depends on the module but gets no sender, poller or endpoint, so its rows are never
        // sent and FAILED ones cannot be requeued.
        withTopic.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(Outbox.class)
                .hasSingleBean(OutboxMessageSender.class)
                .hasSingleBean(OutboxEventListener.class)
                .hasSingleBean(OutboxPoller.class)
                .hasSingleBean(OutboxOperations.class)
                .hasSingleBean(OutboxEndpoint.class)
                .hasSingleBean(OutboxProperties.class));
    }

    @Test
    void aServiceWithoutATopicFailsToStartForWantOfOne() {
        // Guards a default topic: a service that forgot to declare its own would publish where another service's
        // consumer reads.
        runner.run(context -> assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining(OutboxTopic.class.getName()));
    }

    @Test
    void theSendAfterTheCommitRunsOnTheTaskExecutor() {
        // Guards a service that does not enable async itself: the fast path would send on the request thread and
        // wait on the broker before the client gets its answer.
        withTopic.run(context -> assertThat(AopUtils.isAopProxy(context.getBean(OutboxEventListener.class))).isTrue());
    }

    @Test
    void aServicesOwnAsyncSettingsAreKept() {
        // Guards the module's @EnableAsync taking over the processor and resetting the service's attributes.
        withTopic.withUserConfiguration(OrderedAsyncConfig.class).run(context -> {
            AsyncAnnotationBeanPostProcessor processor = context.getBean(
                    TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME,
                    AsyncAnnotationBeanPostProcessor.class
            );

            assertThat(processor.getOrder()).isEqualTo(OrderedAsyncConfig.ORDER);
        });
    }

    @Test
    void theOutboxRefusesToWriteOutsideATransaction() {
        // Guards the dual write: the outbox bean must be the transactional proxy, so a call without the state
        // change's transaction fails instead of committing a row of its own.
        withTopic.withUserConfiguration(TransactionConfig.class).run(context -> assertThatThrownBy(
                () -> context.getBean(Outbox.class).enqueue("TestAggregate", "ref-1", "TestEvent", Map.of())
        ).isInstanceOf(IllegalTransactionStateException.class));
    }

    @Test
    void theModulesEntityAndRepositoryJoinTheServicesScan() {
        // Guards outbox_events staying outside the service's JPA scan, which fails startup on a missing
        // OutboxEventRepository, and a registration that replaced the service's own package instead of adding to it.
        withTopic.withUserConfiguration(ServicePackageConfig.class)
                .run(context -> assertThat(AutoConfigurationPackages.get(context.getBeanFactory()))
                        .containsExactlyInAnyOrder(
                                ServicePackageConfig.PACKAGE,
                                OutboxAutoConfiguration.class.getPackageName()
                        ));
    }

    @Test
    void thePackageIsRegisteredBeforeTheJpaAutoConfigurationsReadIt() {
        // Guards the ordering: Spring Data registers repositories from the packages known when it runs, so a later
        // registration would leave OutboxEventRepository out.
        AutoConfiguration autoConfiguration = OutboxAutoConfiguration.class.getAnnotation(AutoConfiguration.class);

        assertThat(autoConfiguration.before())
                .contains(HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class);
    }

    @Test
    void theFailedRowsGaugeIsRegistered() {
        // Guards the alert signal for FAILED rows, which nothing retries until an operator requeues them.
        withTopic.run(context -> {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            context.getBean(MeterBinder.class).bindTo(registry);

            assertThat(registry.find("outbox.events.failed").gauge()).isNotNull();
        });
    }

    @Test
    void bootFindsTheAutoConfiguration() {
        // Guards the imports file: without its entry a service with the module on its classpath gets no outbox.
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
                .contains(OutboxAutoConfiguration.class.getName());
    }

    @EnableAsync(order = OrderedAsyncConfig.ORDER)
    @Configuration(proxyBeanMethods = false)
    static class OrderedAsyncConfig {
        static final int ORDER = 7;
    }

    @EnableTransactionManagement
    @Configuration(proxyBeanMethods = false)
    static class TransactionConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new DataSourceTransactionManager(mock(DataSource.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackages = ServicePackageConfig.PACKAGE)
    static class ServicePackageConfig {
        static final String PACKAGE = "com.example.service";
    }
}
