package com.yuno.payment.model;

/**
 * Supported payment method types.
 *
 * The routing engine maps each method to a specific provider:
 *   CARD  → Provider A
 *   UPI   → Provider B
 */
public enum PaymentMethod {
    CARD,
    UPI
}
