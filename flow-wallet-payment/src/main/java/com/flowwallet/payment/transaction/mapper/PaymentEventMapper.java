package com.flowwallet.payment.transaction.mapper;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.payment.dto.PaymentIntentResponse;
import com.flowwallet.payment.transaction.PaymentTransaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.UUID;

@Mapper(componentModel = "spring", imports = {UUID.class, KafkaConstants.class})
public interface PaymentEventMapper {
    @Mapping(target = "eventId", expression = "java(UUID.randomUUID().toString())")
    @Mapping(target = "schemaVersion", expression = "java(KafkaConstants.PAYMENT_EVENT_SCHEMA_VERSION)")
    PaymentCompletedEvent toPaymentCompletedEvent(PaymentTransaction transaction, Instant completedAt);

    @Mapping(target = "eventId", expression = "java(UUID.randomUUID().toString())")
    @Mapping(target = "schemaVersion", expression = "java(KafkaConstants.PAYMENT_EVENT_SCHEMA_VERSION)")
    PaymentFailedEvent toPaymentFailedEvent(PaymentTransaction transaction, String reason, Instant failedAt);

    @Mapping(target = "providerData", source = "providerMetadata")
    @Mapping(target = "paymentIntentId", source = "providerTransactionId")
    PaymentIntentResponse toResponse(PaymentTransaction transaction);
}
