package com.yuno.payment.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IdempotencyService.
 * Redis operations are mocked — no real Redis instance needed.
 *
 * Test classification: SANITY + REGRESSION
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        idempotencyService = new IdempotencyService(redisTemplate);
    }

    @Test
    @DisplayName("[SANITY] get() returns stored paymentId for known key")
    void getReturnsStoredValue() {
        String key = "idem-key-001";
        String paymentId = UUID.randomUUID().toString();
        when(valueOps.get("idempotency:" + key)).thenReturn(paymentId);

        String result = idempotencyService.get(key);

        assertThat(result).isEqualTo(paymentId);
    }

    @Test
    @DisplayName("[SANITY] get() returns null for unknown key")
    void getReturnsNullForUnknownKey() {
        when(valueOps.get(anyString())).thenReturn(null);

        String result = idempotencyService.get("unknown-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("[SANITY] store() calls Redis SET with 24h TTL")
    void storeCallsRedisSet() {
        String key = "idem-key-002";
        String paymentId = UUID.randomUUID().toString();

        idempotencyService.store(key, paymentId);

        verify(valueOps).set(eq("idempotency:" + key), eq(paymentId), eq(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("[SANITY] remove() calls Redis DELETE")
    void removeCallsRedisDelete() {
        String key = "idem-key-003";

        idempotencyService.remove(key);

        verify(redisTemplate).delete("idempotency:" + key);
    }

    @Test
    @DisplayName("[REGRESSION] get() returns null gracefully when Redis throws exception")
    void getReturnNullOnRedisException() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis connection refused"));

        // Should not throw — falls back gracefully
        String result = idempotencyService.get("some-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("[REGRESSION] store() does not throw when Redis is unavailable")
    void storeDoesNotThrowOnRedisException() {
        doThrow(new RuntimeException("Redis connection refused"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // Should swallow exception and log warning
        idempotencyService.store("key", "value");
    }

    @Test
    @DisplayName("[REGRESSION] remove() does not throw when Redis is unavailable")
    void removeDoesNotThrowOnRedisException() {
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("Redis down"));

        // Should not propagate exception
        idempotencyService.remove("key");
    }
}
