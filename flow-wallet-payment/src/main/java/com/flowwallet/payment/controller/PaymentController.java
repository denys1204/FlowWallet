package com.flowwallet.payment.controller;

import com.flowwallet.common.dto.CreatePaymentIntentRequest;
import com.flowwallet.common.dto.PaymentIntentResponse;
import com.flowwallet.payment.service.PaymentService;
import com.flowwallet.common.security.CurrentUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService paymentService;

    @PostMapping("/intent")
    public PaymentIntentResponse initiateDeposit(
            @Valid @RequestBody CreatePaymentIntentRequest request,
            @CurrentUserId String userId
    ) {
        return paymentService.initiatePayment(request, userId);
    }
}
