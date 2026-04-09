package com.yuno.payment.provider;

import lombok.Builder;
import lombok.Data;

/**
 * Value object returned by a {@link PaymentProviderConnector} after processing.
 */
@Data
@Builder
public class ProviderResponse {

    private boolean success;

    /** Provider's own transaction/reference ID (present on success) */
    private String providerReference;

    /** Human-readable message for success confirmation or failure reason */
    private String message;

    /** Provider HTTP status code or internal error code */
    private String errorCode;

    public static ProviderResponse success(String reference, String message) {
        return ProviderResponse.builder()
                .success(true)
                .providerReference(reference)
                .message(message)
                .build();
    }

    public static ProviderResponse failure(String errorCode, String message) {
        return ProviderResponse.builder()
                .success(false)
                .errorCode(errorCode)
                .message(message)
                .build();
    }
}
