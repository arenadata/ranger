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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class AuditSpoolPermissionsTest {
    private static final String PREFIX = "xasecure.audit.destination.solr.batch";

    @Parameterized.Parameters(name = "spool={0}")
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {{"batch"}, {"queue"}, {"cache-provider"}});
    }

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final String kind;
    private Properties props;
    private Path directory;

    public AuditSpoolPermissionsTest(String kind) {
        this.kind = kind;
    }

    @Before
    public void setUp() throws Exception {
        directory = temporaryFolder.newFolder("spool").toPath();
        props = new Properties();
        props.setProperty(PREFIX + ".filespool.dir", directory.toString());
        props.setProperty(PREFIX + ".filespool.index.filename", "index.json");
    }

    @Test
    public void unsetPermissionsPreserveExistingSpoolAndIndex() throws Exception {
        assertExistingPermissionsPreserved();
    }

    @Test
    public void blankPermissionsAndDisabledModePreserveExistingPermissions() throws Exception {
        props.setProperty(PREFIX + ".filespool.subdir.mode", "disabled");
        props.setProperty(PREFIX + ".filespool.dir.perms", " ");
        props.setProperty(PREFIX + ".filespool.perms", " ");
        assertExistingPermissionsPreserved();
    }

    @Test
    public void newSpoolUsesLegacyCreationPermissions() throws Exception {
        Files.delete(directory);
        File referenceDir = new File(temporaryFolder.getRoot(), "reference");
        assertTrue(referenceDir.mkdirs());
        File referenceFile = new File(referenceDir, "reference.json");
        assertTrue(referenceFile.createNewFile());

        assertTrue(initSpool());

        assertEquals(Files.getPosixFilePermissions(referenceDir.toPath()), Files.getPosixFilePermissions(directory));
        assertEquals(Files.getPosixFilePermissions(referenceDir.toPath()), Files.getPosixFilePermissions(directory.resolve("archive")));
        assertEquals(Files.getPosixFilePermissions(referenceFile.toPath()), Files.getPosixFilePermissions(directory.resolve("index.json")));
    }

    @Test
    public void explicitFilePermissionsDoNotRequireDirectoryPermissions() throws Exception {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwx---"));
        props.setProperty(PREFIX + ".filespool.perms", "600");

        assertTrue(initSpool());

        assertEquals(PosixFilePermissions.fromString("rwxrwx---"), Files.getPosixFilePermissions(directory));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("index.json")));
    }

    @Test
    public void explicitDirectoryPermissionsDoNotChangeExistingFilePermissions() throws Exception {
        props.setProperty(PREFIX + ".filespool.dir.perms", "770");
        assertExistingPermissionsPreserved();
    }

    @Test
    public void missingSpoolPathDoesNotCreateSpool() throws Exception {
        props.remove(PREFIX + ".filespool.dir");

        assertFalse(initSpool());
        assertArrayEquals(new String[0], directory.toFile().list());
    }

    private void assertExistingPermissionsPreserved() throws Exception {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwx---"));
        Path archive = Files.createDirectory(directory.resolve("archive"));
        Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("rwxrwx---"));
        Path index = Files.createFile(directory.resolve("index.json"));
        Files.setPosixFilePermissions(index, PosixFilePermissions.fromString("rw-------"));

        assertTrue(initSpool());

        assertEquals(PosixFilePermissions.fromString("rwxrwx---"), Files.getPosixFilePermissions(directory));
        assertEquals(PosixFilePermissions.fromString("rwxrwx---"), Files.getPosixFilePermissions(archive));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(index));
    }

    private boolean initSpool() {
        DummyAuditProvider consumer = new DummyAuditProvider();
        if ("batch".equals(kind)) {
            return new AuditFileSpool(new AuditBatchQueue(consumer), consumer).init(props, PREFIX);
        } else if ("queue".equals(kind)) {
            return new AuditFileQueueSpool(consumer).init(props, PREFIX);
        } else {
            return new AuditFileCacheProviderSpool(consumer).init(props, PREFIX);
        }
    }
}
