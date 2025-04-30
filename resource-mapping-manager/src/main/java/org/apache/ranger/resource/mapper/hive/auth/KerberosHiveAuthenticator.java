package org.apache.ranger.resource.mapper.hive.auth;

import java.io.IOException;
import java.security.PrivilegedAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.security.UserGroupInformation;

@Slf4j
@RequiredArgsConstructor
public class KerberosHiveAuthenticator implements HiveAuthenticator {
    private final String principal;
    private final String keytabPath;

    @Override
    public void login() throws Exception {
        UserGroupInformation.loginUserFromKeytab(principal, keytabPath);
    }

    @Override
    public void executeSecurely(Runnable action) throws IOException {
        getUgiWithFreshAuth().doAs((PrivilegedAction<Void>) () -> {
            action.run();
            return null;
        });
    }

    public static UserGroupInformation getUgiWithFreshAuth() throws IOException {
        UserGroupInformation ugi = UserGroupInformation.getLoginUser();

        if (ugi != null) {
            try {
                ugi.checkTGTAndReloginFromKeytab();
            } catch (IOException ioe) {
                log.error("Error renewing TGT and relogin. Ignoring Exception, and continuing with the old TGT", ioe);
            }
        }

        return ugi;
    }
}
