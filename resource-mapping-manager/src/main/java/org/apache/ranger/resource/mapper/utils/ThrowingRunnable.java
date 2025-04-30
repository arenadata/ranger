package org.apache.ranger.resource.mapper.utils;

public interface ThrowingRunnable<E extends Exception> {
    void run() throws E;
}
