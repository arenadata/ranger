package org.apache.ranger.resource.mapper.hive.auth;

public interface HiveAuthenticator {
    void login() throws Exception;

    default void executeSecurely(Runnable action) throws Exception {
        action.run();
    }

    static HiveAuthenticator noOpAuthenticator() {
        return () -> {};
    }
}
