package com.flowwallet.payment.controller;

import com.flowwallet.common.dto.CreatePaymentIntentRequest;
import com.flowwallet.common.dto.PaymentIntentResponse;
import com.flowwallet.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.flowwallet.common.constant.HttpHeaders.USER_ID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;

    @PostMapping("/intent")
    public ResponseEntity<PaymentIntentResponse> initiateDeposit(
            @Valid @RequestBody CreatePaymentIntentRequest request,
            @RequestHeader(USER_ID) String userId
    ) {
        PaymentIntentResponse response = paymentService.initiatePayment(request, userId);
        return ResponseEntity.ok(response);
    }
}
