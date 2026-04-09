package com.yuno.payment.dto;

import com.yuno.payment.model.Payment;
import com.yuno.payment.model.PaymentMethod;
import com.yuno.payment.model.PaymentStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbound response returned to the client for both create and fetch operations.
 */
@Data
@Builder
public class PaymentResponse {

    private UUID id;
    private String idempotencyKey;
    private String merchantId;
    private BigDecimal amount;
    private String currency;
    private PaymentMethod paymentMethod;
    private PaymentStatus status;
    private String providerName;
    private String providerReference;
    private String statusMessage;
    private int retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Factory method to map a Payment entity to the API response DTO.
     */
    public static PaymentResponse from(Payment payment) {
        return PaymentResponse.builder()
                .id(payment.getId())
                .idempotencyKey(payment.getIdempotencyKey())
                .merchantId(payment.getMerchantId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .providerName(payment.getProviderName())
                .providerReference(payment.getProviderReference())
                .statusMessage(payment.getStatusMessage())
                .retryCount(payment.getRetryCount())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }
}
