package com.demo.demo.Service.scheduling.application;

/**
 * Unchecked exception for scheduling business logic errors.
 */
public class SchedulingException extends RuntimeException {
    public SchedulingException(String message) {
        super(message);
    }
}
