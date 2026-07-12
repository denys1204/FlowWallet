package com.flowwallet.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@EnableScheduling
@SpringBootApplication
public class PaymentServiceApplication {
    static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
