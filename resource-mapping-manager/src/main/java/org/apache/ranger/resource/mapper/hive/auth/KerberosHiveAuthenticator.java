/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.resource.mapper.hive.auth;

import java.io.IOException;
import java.security.PrivilegedAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;

@Slf4j
@RequiredArgsConstructor
public class KerberosHiveAuthenticator implements HiveAuthenticator {
    private final String principal;
    private final String keytabPath;
    private final Configuration configuration;

    @Override
    public void login() throws Exception {
        UserGroupInformation.setConfiguration(configuration);
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
