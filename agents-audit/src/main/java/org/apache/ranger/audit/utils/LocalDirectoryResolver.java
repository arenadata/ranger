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
import org.apache.hadoop.security.UserGroupInformation;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class LocalDirectoryResolver {
    public static final String SUBDIR_MODE_DISABLED = "disabled";
    public static final String SUBDIR_MODE_PERUSER  = "peruser";
    public static final String SUBDIR_MODE_PERGROUP = "pergroup";

    private static final Set<PosixFilePermission> PERUSER_DIR_PERMS   = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PERUSER_FILE_PERMS  = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> PERGROUP_DIR_PERMS  = PosixFilePermissions.fromString("rwxrwx---");
    private static final Set<PosixFilePermission> PERGROUP_FILE_PERMS = PosixFilePermissions.fromString("rw-rw----");
    private static final int DIRECTORY_VALIDATION_RETRY_COUNT = 3;
    private static final long DIRECTORY_VALIDATION_RETRY_INTERVAL_MS = 50L;

    private LocalDirectoryResolver() {
    }

    public static ResolvedDirectory resolveAuditSpool(String baseDir, String subdirMode, Set<PosixFilePermission> defaultDirPerms, Set<PosixFilePermission> defaultFilePerms) {
        return resolve(baseDir, subdirMode, defaultDirPerms, defaultFilePerms, "audit spool", "Audit spool path", "Base audit spool directory", "audit spool directory");
    }

    public static ResolvedDirectory resolveLocalDirectory(String baseDir, String subdirMode, Set<PosixFilePermission> defaultDirPerms, Set<PosixFilePermission> defaultFilePerms) {
        return resolve(baseDir, subdirMode, defaultDirPerms, defaultFilePerms, "local directory", "Local directory", "Base local directory", "local directory");
    }

    private static ResolvedDirectory resolve(String baseDir, String subdirMode, Set<PosixFilePermission> defaultDirPerms, Set<PosixFilePermission> defaultFilePerms, String subdirDescription, String directoryLabel, String baseDirectoryLabel, String ownershipLabel) {
        String mode = StringUtils.defaultIfBlank(subdirMode, SUBDIR_MODE_DISABLED).trim().toLowerCase();

        if (SUBDIR_MODE_DISABLED.equals(mode)) {
            return new ResolvedDirectory(null, baseDir, null, defaultDirPerms, defaultFilePerms, null, null, directoryLabel, baseDirectoryLabel, ownershipLabel);
        } else if (SUBDIR_MODE_PERUSER.equals(mode)) {
            String user = sanitizePathElement(getCurrentUser(), "user", subdirDescription);

            return new ResolvedDirectory(baseDir, appendPath(baseDir, user), defaultDirPerms, PERUSER_DIR_PERMS, PERUSER_FILE_PERMS, user, null, directoryLabel, baseDirectoryLabel, ownershipLabel);
        } else if (SUBDIR_MODE_PERGROUP.equals(mode)) {
            String group = sanitizePathElement(getCurrentGroup(subdirDescription), "group", subdirDescription);

            return new ResolvedDirectory(baseDir, appendPath(baseDir, group), defaultDirPerms, PERGROUP_DIR_PERMS, PERGROUP_FILE_PERMS, null, group, directoryLabel, baseDirectoryLabel, ownershipLabel);
        }

        throw new IllegalArgumentException("Unsupported " + subdirDescription + " subdir mode: " + subdirMode);
    }

    private static String appendPath(String baseDir, String child) {
        if (StringUtils.isBlank(baseDir)) {
            return baseDir;
        }

        return new File(baseDir, child).getPath();
    }

    private static String sanitizePathElement(String value, String label, String subdirDescription) {
        String ret = StringUtils.trim(value);

        if (StringUtils.isBlank(ret) || ".".equals(ret) || "..".equals(ret) || ret.contains("..") ||
                ret.indexOf('/') >= 0 || ret.indexOf('\\') >= 0 || ret.indexOf(File.separatorChar) >= 0 ||
                ret.indexOf(File.pathSeparatorChar) >= 0) {
            throw new IllegalArgumentException("Invalid " + label + " value for " + subdirDescription + " subdir: " + value);
        }

        return ret;
    }

    private static String getCurrentUser() {
        try {
            UserGroupInformation ugi = UserGroupInformation.getCurrentUser();

            if (ugi != null && StringUtils.isNotBlank(ugi.getShortUserName())) {
                return ugi.getShortUserName();
            }
        } catch (IOException ignored) {
        }

        return System.getProperty("user.name");
    }

    private static String getCurrentGroup(String subdirDescription) {
        try {
            UserGroupInformation ugi = UserGroupInformation.getCurrentUser();

            if (ugi != null && StringUtils.isNotBlank(ugi.getPrimaryGroupName())) {
                return ugi.getPrimaryGroupName();
            }
        } catch (IOException ignored) {
        }

        throw new IllegalStateException("Unable to determine current user's primary group for " + subdirDescription + " subdir");
    }

    public static final class ResolvedDirectory {
        private final String                    basePath;
        private final String                    path;
        private final Set<PosixFilePermission> baseDirPermissions;
        private final Set<PosixFilePermission> dirPermissions;
        private final Set<PosixFilePermission> filePermissions;
        private final String                    expectedOwner;
        private final String                    expectedGroup;
        private final String                    directoryLabel;
        private final String                    baseDirectoryLabel;
        private final String                    ownershipLabel;

        private ResolvedDirectory(String basePath, String path, Set<PosixFilePermission> baseDirPermissions, Set<PosixFilePermission> dirPermissions, Set<PosixFilePermission> filePermissions, String expectedOwner, String expectedGroup, String directoryLabel, String baseDirectoryLabel, String ownershipLabel) {
            this.basePath           = basePath;
            this.path               = path;
            this.baseDirPermissions = baseDirPermissions;
            this.dirPermissions     = dirPermissions;
            this.filePermissions    = filePermissions;
            this.expectedOwner      = expectedOwner;
            this.expectedGroup      = expectedGroup;
            this.directoryLabel     = directoryLabel;
            this.baseDirectoryLabel = baseDirectoryLabel;
            this.ownershipLabel     = ownershipLabel;
        }

        public String getPath() {
            return path;
        }

        public Set<PosixFilePermission> getDirPermissions() {
            return dirPermissions;
        }

        public Set<PosixFilePermission> getFilePermissions() {
            return filePermissions;
        }

        public void ensureDirectory() throws IOException {
            if (StringUtils.isBlank(path)) {
                return;
            }

            Path directory = Paths.get(path);

            if (StringUtils.isNotBlank(basePath)) {
                ensureBaseDirectory();
            }

            boolean createdByAnotherProcess = false;

            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                validateDirectory(directory, dirPermissions, expectedOwner, expectedGroup, directoryLabel, ownershipLabel);
                return;
            } else if (StringUtils.isNotBlank(basePath)) {
                try {
                    Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(dirPermissions));
                    Files.setPosixFilePermissions(directory, dirPermissions);
                } catch (FileAlreadyExistsException ignored) {
                    // Another local process can create the same per-user/per-group subdirectory concurrently.
                    createdByAnotherProcess = true;
                }
            } else if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(dirPermissions));
                Files.setPosixFilePermissions(directory, dirPermissions);
            }

            if (createdByAnotherProcess) {
                validateDirectoryAfterConcurrentCreate(directory, dirPermissions, expectedOwner, expectedGroup, directoryLabel, ownershipLabel);
            } else {
                validateDirectory(directory, dirPermissions, expectedOwner, expectedGroup, directoryLabel, ownershipLabel);
            }
        }

        private void ensureBaseDirectory() throws IOException {
            Path baseDirectory = Paths.get(basePath);

            if (!Files.exists(baseDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(baseDirectoryLabel + " does not exist: " + baseDirectory);
            }

            validateDirectory(baseDirectory, baseDirPermissions, null, null, baseDirectoryLabel, ownershipLabel);
        }

        private void validateDirectory(Path directory, Set<PosixFilePermission> expectedPermissions, String expectedOwner, String expectedGroup, String label, String ownershipLabel) throws IOException {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(label + " is not a regular directory: " + directory);
            }

            PosixFileAttributeView view = Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);

            if (view != null) {
                PosixFileAttributes attrs = view.readAttributes();

                if (expectedPermissions != null && !attrs.permissions().equals(expectedPermissions)) {
                    throw new IOException("Unsafe permissions on " + StringUtils.lowerCase(label) + " " + directory + ": expected " + expectedPermissions + ", actual " + attrs.permissions());
                }

                if (expectedOwner != null && !StringUtils.equals(attrs.owner().getName(), expectedOwner)) {
                    throw new IOException("Unexpected owner on " + ownershipLabel + " " + directory + ": expected " + expectedOwner + ", actual " + attrs.owner().getName());
                }

                if (expectedGroup != null && !StringUtils.equals(attrs.group().getName(), expectedGroup)) {
                    throw new IOException("Unexpected group on " + ownershipLabel + " " + directory + ": expected " + expectedGroup + ", actual " + attrs.group().getName());
                }
            }
        }

        private void validateDirectoryAfterConcurrentCreate(Path directory, Set<PosixFilePermission> expectedPermissions, String expectedOwner, String expectedGroup, String label, String ownershipLabel) throws IOException {
            IOException lastException = null;

            for (int attempt = 0; attempt < DIRECTORY_VALIDATION_RETRY_COUNT; attempt++) {
                try {
                    validateDirectory(directory, expectedPermissions, expectedOwner, expectedGroup, label, ownershipLabel);
                    return;
                } catch (IOException exception) {
                    lastException = exception;

                    if (attempt + 1 < DIRECTORY_VALIDATION_RETRY_COUNT) {
                        try {
                            Thread.sleep(DIRECTORY_VALIDATION_RETRY_INTERVAL_MS);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            exception.addSuppressed(interruptedException);
                            throw exception;
                        }
                    }
                }
            }

            throw lastException;
        }
    }
}
