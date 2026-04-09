package com.yuno.payment.repository;

import com.yuno.payment.model.Payment;
import com.yuno.payment.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for Payment persistence.
 * All queries run against PostgreSQL in production.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Find a payment by its idempotency key — used to short-circuit duplicate requests.
     */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * List all payments belonging to a specific merchant.
     */
    List<Payment> findByMerchantId(String merchantId);

    /**
     * List all payments in a given status — useful for monitoring and reprocessing.
     */
    List<Payment> findByStatus(PaymentStatus status);
}
