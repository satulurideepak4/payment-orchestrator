package com.yuno.payment.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Core Payment entity persisted to PostgreSQL.
 *
 * Tracks the full lifecycle of a payment from PENDING through to
 * SUCCESS or FAILED, including which provider processed it and
 * how many retry attempts were made.
 */
@Entity
@Table(name = "payments", indexes = {
    @Index(name = "idx_idempotency_key", columnList = "idempotencyKey", unique = true),
    @Index(name = "idx_payment_status", columnList = "status"),
    @Index(name = "idx_merchant_id", columnList = "merchantId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Client-supplied idempotency key — guarantees at-most-once processing
     * even if the request is retried by the caller.
     */
    @Column(nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String merchantId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** ISO 4217 currency code, e.g. USD, INR */
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    /** Name of the downstream provider that processed this payment */
    @Column(length = 64)
    private String providerName;

    /** Transaction reference returned by the provider */
    @Column(length = 256)
    private String providerReference;

    /** Human-readable message (e.g. provider error description) */
    @Column(length = 512)
    private String statusMessage;

    @Column(nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
