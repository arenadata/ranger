package org.apache.ranger.audit.utils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class FileUtilTest {

    @Test
    public void testParsePermissions755() {
        // "755" -> "rwxr-xr-x"
        Set<PosixFilePermission> actual = AuditFileUtil.parsePermissions("755");
        Set<PosixFilePermission> expected = PosixFilePermissions.fromString("rwxr-xr-x");
        assertEquals("Permissions for '755' should be 'rwxr-xr-x'", expected, actual);
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

}
