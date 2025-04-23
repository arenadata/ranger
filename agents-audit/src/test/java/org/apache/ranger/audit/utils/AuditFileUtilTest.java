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
package org.apache.ranger.audit.utils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AuditFileUtilTest {

    @Test
    public void testParsePermissions755() {
        // "755" -> "rwxr-xr-x"
        Set<PosixFilePermission> actual = AuditFileUtil.parsePermissions("755");
        Set<PosixFilePermission> expected = PosixFilePermissions.fromString("rwxr-xr-x");
        assertEquals("Permissions for '755' should be 'rwxr-xr-x'", expected, actual);
    }

    @Test
    public void testParsePermissions666() {
        // "666" -> "rw-rw-rw-"
        Set<PosixFilePermission> actual = AuditFileUtil.parsePermissions("666");
        Set<PosixFilePermission> expected = PosixFilePermissions.fromString("rw-rw-rw-");
        assertEquals("Permissions for '666' should be 'rw-rw-rw-'", expected, actual);
    }

    @Test
    public void testParsePermissions777() {
        // "777" -> "rwxrwxrwx"
        Set<PosixFilePermission> actual = AuditFileUtil.parsePermissions("777");
        Set<PosixFilePermission> expected = PosixFilePermissions.fromString("rwxrwxrwx");
        assertEquals("Permissions for '777' should be 'rwxrwxrwx'", expected, actual);
    }

    @Test
    public void testParsePermissions644() {
        // "644" -> "rw-r--r--"
        Set<PosixFilePermission> actual = AuditFileUtil.parsePermissions("644");
        Set<PosixFilePermission> expected = PosixFilePermissions.fromString("rw-r--r--");
        assertEquals("Permissions for '644' should be 'rw-r--r--'", expected, actual);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePermissionsNull() {
        AuditFileUtil.parsePermissions(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePermissionsShortString() {
        AuditFileUtil.parsePermissions("75");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePermissionsLongString() {
        AuditFileUtil.parsePermissions("7555");
    }

    @Test
    public void testSetPermissionsOnExistingFile() throws IOException {
        File tempFile = File.createTempFile("testFile", ".txt");
        try {
            Set<PosixFilePermission> expectedPerms = PosixFilePermissions.fromString("rw-r--r--");
            AuditFileUtil.setPermissions(tempFile, expectedPerms);
            Set<PosixFilePermission> actualPerms = Files.getPosixFilePermissions(tempFile.toPath());
                assertEquals("Permissions should be set correctly", expectedPerms, actualPerms);
        } finally {
            tempFile.delete();
        }
    }

    @Test(expected = NoSuchFileException.class)
    public void testSetPermissionsOnNonExistentFile() throws IOException {
        File nonExistentFile = new File("nonexistentfile.txt");
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
        AuditFileUtil.setPermissions(nonExistentFile, perms);
    }

    @Test
    public void testCreateDirectoryWithPermissions() throws Exception {
        Path tempBaseDir = Files.createTempDirectory("testCreateDir");
        try {
            File newDir = new File(tempBaseDir.toFile(), "newSubDir");
            Set<PosixFilePermission> expectedPerms = PosixFilePermissions.fromString("rwxr-xr-x");

            AuditFileUtil.createDirectoryWithPermissions(newDir, expectedPerms);

            assertTrue("Directory should exist", newDir.exists());

            Set<PosixFilePermission> actualPerms = Files.getPosixFilePermissions(newDir.toPath());
            assertEquals("Directory permissions should match expected permissions", expectedPerms, actualPerms);
        } finally {
            Files.walk(tempBaseDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {}
                    });
        }
    }

    @Test
    public void testCreateDirectoryWithPermissions777() throws Exception {
        Path tempBaseDir = Files.createTempDirectory("testCreateDir");
        try {
            File newDir = new File(tempBaseDir.toFile(), "newSubDir");
            // "777" corresponds to "rwxrwxrwx"
            Set<PosixFilePermission> expectedPerms = PosixFilePermissions.fromString("rwxrwxrwx");

            AuditFileUtil.createDirectoryWithPermissions(newDir, expectedPerms);

            assertTrue("Directory should exist", newDir.exists());

            Set<PosixFilePermission> actualPerms = Files.getPosixFilePermissions(newDir.toPath());
            assertEquals("Directory permissions should match expected permissions", expectedPerms, actualPerms);
        } finally {
            Files.walk(tempBaseDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignore cleanup exceptions
                        }
                    });
        }
    }

}
