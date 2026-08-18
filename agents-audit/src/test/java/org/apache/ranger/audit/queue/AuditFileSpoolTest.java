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
package org.apache.ranger.audit.queue;

import org.apache.ranger.audit.provider.DummyAuditProvider;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AuditFileSpoolTest {
    @Test
    public void testSolrBatchFileSpoolSubdirModeIsApplied() throws Exception {
        Path baseDir = Files.createTempDirectory("audit-solr-spool");

        try {
            Files.setPosixFilePermissions(baseDir, PosixFilePermissions.fromString("rwxr-xr-x"));

            String     propPrefix = "xasecure.audit.destination.solr.batch";
            Properties props      = new Properties();

            props.setProperty(propPrefix + "." + AuditFileSpool.PROP_FILE_SPOOL_LOCAL_DIR, baseDir.toString());
            props.setProperty(propPrefix + "." + AuditFileSpool.PROP_FILE_SPOOL_SUBDIR_MODE, "peruser");

            DummyAuditProvider consumer = new DummyAuditProvider();
            AuditFileSpool     spool    = new AuditFileSpool(new AuditBatchQueue(consumer), consumer);

            assertTrue(spool.init(props, propPrefix));
            assertEquals(baseDir, spool.logFolder.toPath().getParent());
            assertTrue(Files.isDirectory(spool.logFolder.toPath()));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(spool.logFolder.toPath()));
        } finally {
            deleteRecursively(baseDir);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }

        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(currentPath -> {
                try {
                    Files.deleteIfExists(currentPath);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
