package com.flowwallet.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PaymentServiceApplication {
    static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
