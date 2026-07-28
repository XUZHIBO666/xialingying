package com.demo.demo.Service.scheduling.execution;

public record PushResult(boolean success, String errorCode) {

    public static PushResult ok() { return new PushResult(true, null); }
    public static PushResult failed(String errorCode) { return new PushResult(false, errorCode); }
}
