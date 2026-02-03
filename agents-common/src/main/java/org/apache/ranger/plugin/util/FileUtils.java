package org.apache.ranger.plugin.util;

import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.*;
import java.util.Set;

public class FileUtils {
    public static void setPermissions(File file, Set<PosixFilePermission> perms) throws IOException {
        Path path = file.toPath();
        Files.setPosixFilePermissions(path, perms);
    }

    public static Set<PosixFilePermission> parsePermissions(String permStr) {
        if (StringUtils.length(permStr) != 3) {
            throw new IllegalArgumentException("The permission string must consist of 3 digits, for example '755'");
        }
        StringBuilder symbolic = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            char c = permStr.charAt(i);
            int value = c - '0';
            symbolic.append((value & 4) != 0 ? "r" : "-");
            symbolic.append((value & 2) != 0 ? "w" : "-");
            symbolic.append((value & 1) != 0 ? "x" : "-");
        }
        return PosixFilePermissions.fromString(symbolic.toString());
    }

    public static void createDirectoryWithPermissions(File dir, Set<PosixFilePermission> perms) throws Exception {
        FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(perms);
        Path path = dir.toPath();
        Files.createDirectories(path, attr);
        Files.setPosixFilePermissions(path, perms);
    }
}

