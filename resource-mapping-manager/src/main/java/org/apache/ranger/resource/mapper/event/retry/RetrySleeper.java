package org.apache.ranger.resource.mapper.event.retry;

public interface RetrySleeper {
    void sleepForMs(long durationMs) throws InterruptedException;
}