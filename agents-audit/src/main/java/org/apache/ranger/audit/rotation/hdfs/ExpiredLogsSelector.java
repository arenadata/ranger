/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ranger.audit.rotation.hdfs;

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.hadoop.fs.FileStatus;

public class ExpiredLogsSelector implements StaleLogsSelector {

    private final long retentionMillis;
    private final Supplier<Long> currentTimeSupplier;

    public ExpiredLogsSelector(long retentionMillis) {
        this(retentionMillis, System::currentTimeMillis);
    }

    public ExpiredLogsSelector(long retentionMillis, Supplier<Long> currentTimeSupplier) {
        this.retentionMillis = retentionMillis;
        this.currentTimeSupplier = currentTimeSupplier;
    }

    @Override
    public Set<FileStatus> getStaleFiles(Set<FileStatus> statuses) {
        long now = currentTimeSupplier.get();
        return statuses.stream()
            .filter(status -> !status.isDirectory())
            .filter(status -> now - status.getModificationTime() >= retentionMillis)
            .collect(Collectors.toSet());
    }
}
