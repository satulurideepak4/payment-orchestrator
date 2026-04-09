package com.yuno.payment.dto;

import com.yuno.payment.model.PaymentMethod;
import com.yuno.payment.model.PaymentStatus;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Inbound request payload for creating a new payment.
 * All fields are validated before the request reaches the service layer.
 */
@Data
public class CreatePaymentRequest {

    /**
     * Caller-supplied idempotency key.
     * The same key can be resubmitted safely — the original response is returned.
     */
    @NotBlank(message = "idempotencyKey must not be blank")
    @Size(min = 8, max = 128, message = "idempotencyKey must be between 8 and 128 characters")
    private String idempotencyKey;

    @NotBlank(message = "merchantId must not be blank")
    @Size(max = 64)
    private String merchantId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than zero")
    @Digits(integer = 15, fraction = 4, message = "amount format is invalid")
    private BigDecimal amount;

    /** ISO 4217 currency code */
    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO 4217 code (e.g. USD)")
    private String currency;

    @NotNull(message = "paymentMethod is required")
    private PaymentMethod paymentMethod;
}

