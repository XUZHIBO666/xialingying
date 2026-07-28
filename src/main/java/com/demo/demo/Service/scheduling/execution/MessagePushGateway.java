package com.demo.demo.Service.scheduling.execution;

/**
 * Abstraction for pushing messages to users via the default Bot.
 * TASK-010 provides the iLink implementation.
 */
public interface MessagePushGateway {

    PushResult pushText(PushRequest request);
}
