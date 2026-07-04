package com.flowwallet.payment.transaction.mapper;

import com.flowwallet.common.constant.KafkaConstants;
import com.flowwallet.common.event.PaymentCompletedEvent;
import com.flowwallet.payment.outbox.OutboxEvent;
import com.flowwallet.payment.transaction.PaymentTransaction;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;

@Mapper(componentModel = "spring", imports = {Instant.class, KafkaConstants.class})
public interface PaymentEventMapper {
    @Mapping(target = "completedAt", expression = "java(Instant.now())")
    @Mapping(target = "paymentIntentId", source = "providerTransactionId")
    PaymentCompletedEvent toPaymentCompletedEvent(PaymentTransaction transaction);

    @Mapping(target = "aggregateType", constant = KafkaConstants.AGGREGATE_TYPE_PAYMENT_TRANSACTION)
    @Mapping(target = "aggregateId", source = "transaction.transactionReference")
    @Mapping(target = "eventType", constant = KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED)
    @Mapping(target = "payload", source = "payload")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "processedAt", ignore = true)
    OutboxEvent toOutboxEvent(PaymentTransaction transaction, String payload);

    PaymentRequestContext toRequestContext(PaymentTransaction transaction);
}
