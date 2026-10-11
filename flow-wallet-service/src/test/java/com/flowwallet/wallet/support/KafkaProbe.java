package com.flowwallet.wallet.support;

import com.flowwallet.contract.constant.KafkaConstants;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;

/**
 * Publishes records as another service would and waits for the service's consumer group to get past them. Waiting
 * on the committed offset rather than on a visible effect is what lets a test assert that a record changed nothing:
 * with {@code ack-mode: RECORD} the group commits a record's offset only after its listener has returned.
 */
public final class KafkaProbe {
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final String bootstrapServers;
    private final KafkaProducer<String, String> producer;
    private final AdminClient admin;

    public KafkaProbe(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
        this.admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    /**
     * Sends a record the way the outbox does: keyed by the transaction reference, with the {@code eventType} header.
     */
    public RecordMetadata publish(String topic, String key, String eventType, String json) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json);
        record.headers().add(KafkaConstants.HEADER_EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
        try {
            return producer.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing to " + topic, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not publish to " + topic, e.getCause());
        }
    }

    /**
     * Returns once the group has committed an offset past the record, so its listener has finished with it.
     */
    public void awaitConsumed(String groupId, RecordMetadata record) {
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        await().until(() -> committedOffset(groupId, partition) > record.offset());
    }

    /**
     * How many records the topic holds across its partitions, for a test that asserts a dead-letter topic gained
     * nothing. Compare it after {@link #awaitConsumed}: the error handler publishes a dead letter before the offset of
     * the record it gives up on is committed.
     */
    public long recordCount(String topic) {
        return endOffsets(topic).values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * Where each partition of the topic ends now: take it before an action, then read what the action added with
     * {@link #recordsSince}.
     */
    public Map<TopicPartition, Long> endOffsets(String topic) {
        try {
            Map<TopicPartition, OffsetSpec> partitions = admin.describeTopics(List.of(topic))
                    .allTopicNames()
                    .get()
                    .get(topic)
                    .partitions()
                    .stream()
                    .collect(Collectors.toMap(p -> new TopicPartition(topic, p.partition()), p -> OffsetSpec.latest()));
            return admin.listOffsets(partitions).all().get().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the offsets of " + topic, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not read the offsets of " + topic, e.getCause());
        }
    }

    /**
     * Every record written to the partitions after the given offsets, read without a consumer group so that no
     * service's group is disturbed.
     */
    public List<ConsumerRecord<String, String>> recordsSince(Map<TopicPartition, Long> start) {
        Map<TopicPartition, Long> end = endOffsets(start.keySet().iterator().next().topic());
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ))) {
            consumer.assign(start.keySet());
            start.forEach(consumer::seek);
            long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
            while (start.keySet().stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Could not read " + start.keySet() + " up to " + end + " within "
                            + READ_TIMEOUT.toSeconds() + " s");
                }
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        return records;
    }

    /**
     * One setting of a topic as the broker holds it, such as {@code retention.ms}.
     */
    public String topicConfig(String topic, String name) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        try {
            return admin.describeConfigs(List.of(resource)).all().get().get(resource).get(name).value();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while describing " + topic, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not describe " + topic, e.getCause());
        }
    }

    private long committedOffset(String groupId, TopicPartition partition)
            throws ExecutionException, InterruptedException {
        Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata()
                .get();
        OffsetAndMetadata committed = offsets.get(partition);
        return committed == null ? -1 : committed.offset();
    }
}
