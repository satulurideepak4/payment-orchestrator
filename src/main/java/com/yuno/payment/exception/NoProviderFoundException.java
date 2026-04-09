package com.yuno.payment.exception;

public class NoProviderFoundException extends RuntimeException {
    public NoProviderFoundException(String message) {
        super(message);
    }
}
