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

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public class AuditFileUtil {
    public static final String SUBDIR_MODE_DISABLED = "disabled";
    public static final String SUBDIR_MODE_PERUSER  = "peruser";
    public static final String SUBDIR_MODE_PERGROUP = "pergroup";

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

    public static ResolvedDirectory resolveDirectory(String baseDir, String subdirMode, Set<PosixFilePermission> defaultDirPerms, Set<PosixFilePermission> defaultFilePerms) {
        return new ResolvedDirectory(LocalDirectoryResolver.resolveAuditSpool(baseDir, subdirMode, defaultDirPerms, defaultFilePerms));
    }

    public static final class ResolvedDirectory {
        private final LocalDirectoryResolver.ResolvedDirectory delegate;

        private ResolvedDirectory(LocalDirectoryResolver.ResolvedDirectory delegate) {
            this.delegate = delegate;
        }

        public String getPath() {
            return delegate.getPath();
        }

        public Set<PosixFilePermission> getDirPermissions() {
            return delegate.getDirPermissions();
        }

        public Set<PosixFilePermission> getFilePermissions() {
            return delegate.getFilePermissions();
        }

        public void ensureDirectory() throws IOException {
            delegate.ensureDirectory();
        }

        public void ensureChildDirectory(File directory) throws IOException {
            delegate.ensureChildDirectory(directory);
        }

        public void secureFile(File file) throws IOException {
            delegate.secureFile(file);
        }
    }

}
