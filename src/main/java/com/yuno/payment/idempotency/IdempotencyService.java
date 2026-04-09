package com.yuno.payment.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Idempotency store backed by Redis.
 *
 * When a payment request arrives, the orchestrator checks Redis for the
 * idempotency key before touching PostgreSQL. This provides a fast-path
 * short-circuit that avoids duplicate processing even under concurrent retries.
 *
 * Key TTL is 24 hours — sufficient for most payment retry windows.
 * The value stored is the payment UUID so the caller can be redirected to
 * the existing record.
 *
 * Fallback: if Redis is unavailable, the service falls back to the DB-level
 * unique constraint on idempotency_key (defined on the Payment entity).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * Returns the stored payment ID for this key, or null if not present.
     */
    public String get(String idempotencyKey) {
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        } catch (Exception e) {
            log.warn("Redis GET failed for key={}, falling back to DB check. Error: {}", idempotencyKey, e.getMessage());
            return null;
        }
    }

    /**
     * Stores the mapping from idempotency key → payment UUID with a 24h TTL.
     */
    public void store(String idempotencyKey, String paymentId) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + idempotencyKey, paymentId, TTL);
            log.debug("Stored idempotency key={} → paymentId={}", idempotencyKey, paymentId);
        } catch (Exception e) {
            log.warn("Redis SET failed for key={}. Idempotency will rely on DB constraint. Error: {}", idempotencyKey, e.getMessage());
        }
    }

    /**
     * Removes the mapping (used in tests or manual reset scenarios).
     */
    public void remove(String idempotencyKey) {
        try {
            redisTemplate.delete(KEY_PREFIX + idempotencyKey);
        } catch (Exception e) {
            log.warn("Redis DELETE failed for key={}. Error: {}", idempotencyKey, e.getMessage());
        }
    }
}
