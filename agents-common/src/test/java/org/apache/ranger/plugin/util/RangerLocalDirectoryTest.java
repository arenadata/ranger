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

package org.apache.ranger.plugin.util;

import org.apache.hadoop.security.UserGroupInformation;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.PrivilegedExceptionAction;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RangerLocalDirectoryTest {
	@Test
	public void testPerUserDirectoryUsesPrivatePermissions() throws Exception {
		Path baseDir = Files.createTempDirectory("ranger-policy-cache");

		try {
			Files.setPosixFilePermissions(baseDir, PosixFilePermissions.fromString("rwxr-xr-x"));
			RangerLocalDirectory.ResolvedDirectory directory = RangerLocalDirectory.resolve(baseDir.toString(),
					RangerLocalDirectory.SUBDIR_MODE_PERUSER,
					FileUtils.parsePermissions("755"),
					FileUtils.parsePermissions("644"));

			directory.ensureDirectory();

			Path resolvedPath = Paths.get(directory.getPath());
			assertTrue(Files.isDirectory(resolvedPath));
			assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(baseDir));
			assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(resolvedPath));
			assertEquals(PosixFilePermissions.fromString("rw-------"), directory.getFilePermissions());
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testPerUserDirectoryNamedByUgiUserButOwnedByProcessUser() throws Exception {
		Path baseDir = Files.createTempDirectory("ranger-policy-cache");

		try {
			Files.setPosixFilePermissions(baseDir, PosixFilePermissions.fromString("rwxr-xr-x"));
			UserGroupInformation user = UserGroupInformation.createRemoteUser("spark_user_test");
			RangerLocalDirectory.ResolvedDirectory directory = user.doAs((PrivilegedExceptionAction<RangerLocalDirectory.ResolvedDirectory>) () ->
					RangerLocalDirectory.resolve(baseDir.toString(),
							RangerLocalDirectory.SUBDIR_MODE_PERUSER,
							FileUtils.parsePermissions("755"),
							FileUtils.parsePermissions("644")));

			directory.ensureDirectory();

			Path resolvedPath = Paths.get(directory.getPath());
			assertEquals("spark_user_test", resolvedPath.getFileName().toString());
			assertTrue(Files.isDirectory(resolvedPath));
			assertEquals(System.getProperty("user.name"), Files.getOwner(resolvedPath).getName());
			assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(resolvedPath));
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testPerGroupDirectoryUsesGroupPermissions() throws Exception {
		Path baseDir = Files.createTempDirectory("ranger-policy-cache");

		try {
			Files.setPosixFilePermissions(baseDir, PosixFilePermissions.fromString("rwxr-xr-x"));
			RangerLocalDirectory.ResolvedDirectory directory = RangerLocalDirectory.resolve(baseDir.toString(),
					RangerLocalDirectory.SUBDIR_MODE_PERGROUP,
					FileUtils.parsePermissions("755"),
					FileUtils.parsePermissions("644"));

			directory.ensureDirectory();

			Path resolvedPath = Paths.get(directory.getPath());
			assertTrue(Files.isDirectory(resolvedPath));
			assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(baseDir));
			assertEquals(PosixFilePermissions.fromString("rwxrwx---"), Files.getPosixFilePermissions(resolvedPath));
			assertEquals(PosixFilePermissions.fromString("rw-rw----"), directory.getFilePermissions());
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testPerGroupFileAndChildDirectoryUseGroupPermissions() throws Exception {
		Path baseDir = Files.createTempDirectory("ranger-policy-cache");

		try {
			Files.setPosixFilePermissions(baseDir, PosixFilePermissions.fromString("rwxr-xr-x"));
			RangerLocalDirectory.ResolvedDirectory directory = RangerLocalDirectory.resolve(baseDir.toString(),
					RangerLocalDirectory.SUBDIR_MODE_PERGROUP,
					FileUtils.parsePermissions("755"),
					FileUtils.parsePermissions("644"));

			directory.ensureDirectory();

			Path resolvedPath = Paths.get(directory.getPath());
			Path archivePath  = resolvedPath.resolve("archive");
			Path cacheFile    = resolvedPath.resolve("policy-cache.json");

			directory.ensureChildDirectory(archivePath.toFile());
			Files.createFile(cacheFile);
			directory.secureFile(cacheFile.toFile());

			GroupPrincipal expectedGroup = Files.readAttributes(resolvedPath, java.nio.file.attribute.PosixFileAttributes.class).group();

			assertEquals(PosixFilePermissions.fromString("rwxrwx---"), Files.getPosixFilePermissions(archivePath));
			assertEquals(PosixFilePermissions.fromString("rw-rw----"), Files.getPosixFilePermissions(cacheFile));
			assertEquals(expectedGroup.getName(), Files.readAttributes(archivePath, java.nio.file.attribute.PosixFileAttributes.class).group().getName());
			assertEquals(expectedGroup.getName(), Files.readAttributes(cacheFile, java.nio.file.attribute.PosixFileAttributes.class).group().getName());
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testPerGroupDirectoryPrefersNonPrivateUserGroup() throws Exception {
		Path baseDir = Files.createTempDirectory("ranger-policy-cache");

		try {
			UserGroupInformation user = UserGroupInformation.createUserForTesting("spark_user2", new String[] {"spark_user2", "spark_group"});
			RangerLocalDirectory.ResolvedDirectory directory = user.doAs((PrivilegedExceptionAction<RangerLocalDirectory.ResolvedDirectory>) () ->
					RangerLocalDirectory.resolve(baseDir.toString(),
							RangerLocalDirectory.SUBDIR_MODE_PERGROUP,
							FileUtils.parsePermissions("755"),
							FileUtils.parsePermissions("644")));

			assertEquals("spark_group", Paths.get(directory.getPath()).getFileName().toString());
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test(expected = IOException.class)
	public void testUnsafeExistingDirectoryPermissionsAreRejected() throws Exception {
		Path baseDir = Files.createTempDirectory("ranger-policy-cache");

		try {
			Files.setPosixFilePermissions(baseDir, PosixFilePermissions.fromString("rwxr-xr-x"));
			RangerLocalDirectory.ResolvedDirectory directory = RangerLocalDirectory.resolve(baseDir.toString(),
					RangerLocalDirectory.SUBDIR_MODE_PERUSER,
					FileUtils.parsePermissions("755"),
					FileUtils.parsePermissions("644"));
			Path resolvedPath = Paths.get(directory.getPath());

			Files.createDirectories(resolvedPath);
			Files.setPosixFilePermissions(resolvedPath, PosixFilePermissions.fromString("rwxr-xr-x"));

			directory.ensureDirectory();
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testPerUserDirectoryRequiresExistingBaseDirectory() throws Exception {
		Path tempDir = Files.createTempDirectory("ranger-policy-cache-parent");

		try {
			Path baseDir = tempDir.resolve("policy-cache");
			RangerLocalDirectory.ResolvedDirectory directory = RangerLocalDirectory.resolve(baseDir.toString(),
					RangerLocalDirectory.SUBDIR_MODE_PERUSER,
					FileUtils.parsePermissions("755"),
					FileUtils.parsePermissions("644"));

			try {
				directory.ensureDirectory();
				fail("Expected missing base directory to be rejected");
			} catch (IOException exception) {
				assertTrue(exception.getMessage().contains("Base local directory does not exist"));
			}

			assertFalse(Files.exists(baseDir));
		} finally {
			deleteRecursively(tempDir);
		}
	}

	@Test
	public void testPerUserDirectoryRejectsUnsafeBasePermissions() throws Exception {
		Path baseDir = Files.createTempDirectory("ranger-policy-cache");

		try {
			RangerLocalDirectory.ResolvedDirectory directory = RangerLocalDirectory.resolve(baseDir.toString(),
					RangerLocalDirectory.SUBDIR_MODE_PERUSER,
					FileUtils.parsePermissions("755"),
					FileUtils.parsePermissions("644"));
			Path resolvedPath = Paths.get(directory.getPath());

			try {
				directory.ensureDirectory();
				fail("Expected unsafe base directory permissions to be rejected");
			} catch (IOException exception) {
				assertTrue(exception.getMessage().contains("Unsafe permissions on base local directory"));
			}

			assertFalse(Files.exists(resolvedPath));
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void testUnknownSubdirModeIsRejected() {
		RangerLocalDirectory.resolve("/tmp/ranger-cache", "unknown", FileUtils.parsePermissions("755"), FileUtils.parsePermissions("644"));
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
