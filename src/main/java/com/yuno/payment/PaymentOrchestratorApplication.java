package com.yuno.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the Yuno Payment Orchestrator Service.
 *
 * This service acts as a payment orchestration layer, routing payment requests
 * to the appropriate provider (Provider A for CARD, Provider B for UPI),
 * enforcing idempotency, retrying failed attempts, and tracking payment status.
 */
@SpringBootApplication
@EnableAsync
public class PaymentOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentOrchestratorApplication.class, args);
    }
}
