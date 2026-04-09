package com.yuno.payment.model;

/**
 * Lifecycle states of a payment.
 *
 * State machine:
 *   PENDING → PROCESSING → SUCCESS
 *                       └→ FAILED
 *
 * PENDING     : Payment record created, not yet sent to provider.
 * PROCESSING  : Request dispatched to provider; awaiting response.
 * SUCCESS     : Provider confirmed the payment.
 * FAILED      : All retry attempts exhausted; provider returned error.
 */
public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED
}
