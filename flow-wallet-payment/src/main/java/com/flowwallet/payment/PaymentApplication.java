package com.flowwallet.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PaymentApplication {
    static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
