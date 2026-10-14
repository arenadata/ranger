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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ranger.admin.client.AbstractRangerAdminClient;
import org.apache.ranger.admin.client.RangerAdminClientAccessDeniedException;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngineOptions;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.PrivilegedExceptionAction;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PolicyRefresherCacheSubdirTest {
	private static final String SERVICE_NAME   = "dev_hive";
	private static final String APP_ID         = "hive";
	private static final long   POLICY_VERSION = 17L;
	private static final String CACHE_FILE     = APP_ID + "_" + SERVICE_NAME + ".json";
	private static final Gson   GSON           = new GsonBuilder().setDateFormat("yyyyMMdd-HH:mm:ss.SSSZ").create();

	@Test
	public void testPerUserCacheFileIsOwnedByProcessUser() throws Exception {
		Path baseDir = createBaseDir();

		try {
			UserGroupInformation user = UserGroupInformation.createRemoteUser("spark_user_test");
			user.doAs((PrivilegedExceptionAction<Void>) () -> {
				try (Session session = startSession(baseDir, RangerLocalDirectory.SUBDIR_MODE_PERUSER, new ScriptedAdminClient(ScriptedAdminClient.Mode.SERVE), false)) {
					Path userDir   = baseDir.resolve("spark_user_test");
					Path cacheFile = userDir.resolve(CACHE_FILE);

					assertTrue(Files.isDirectory(userDir));
					assertTrue(Files.isRegularFile(cacheFile));
					assertEquals(System.getProperty("user.name"), Files.getOwner(userDir).getName());
					assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(userDir));
					assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(cacheFile));
					assertEquals(POLICY_VERSION, session.plugin.getPoliciesVersion());
				}
				return null;
			});
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testCacheIsReloadedWhenAdminIsUnreachable() throws Exception {
		Path baseDir = createBaseDir();

		try {
			UserGroupInformation user = UserGroupInformation.createRemoteUser("spark_user_test");
			user.doAs((PrivilegedExceptionAction<Void>) () -> {
				try (Session ignored = startSession(baseDir, RangerLocalDirectory.SUBDIR_MODE_PERUSER, new ScriptedAdminClient(ScriptedAdminClient.Mode.SERVE), false)) {
					assertTrue(Files.isRegularFile(baseDir.resolve("spark_user_test").resolve(CACHE_FILE)));
				}

				try (Session session = startSession(baseDir, RangerLocalDirectory.SUBDIR_MODE_PERUSER, new ScriptedAdminClient(ScriptedAdminClient.Mode.UNREACHABLE), false)) {
					assertEquals(POLICY_VERSION, session.plugin.getPoliciesVersion());
					assertAllowed(session.plugin);
				}
				return null;
			});
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testDisabledModeWritesCacheIntoBaseDirectory() throws Exception {
		Path baseDir = createBaseDir();

		try (Session session = startSession(baseDir, RangerLocalDirectory.SUBDIR_MODE_DISABLED, new ScriptedAdminClient(ScriptedAdminClient.Mode.SERVE), false)) {
			assertTrue(Files.isRegularFile(baseDir.resolve(CACHE_FILE)));
			assertEquals(POLICY_VERSION, session.plugin.getPoliciesVersion());
			try (java.util.stream.Stream<Path> children = Files.list(baseDir)) {
				assertFalse(children.anyMatch(Files::isDirectory));
			}
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testPerGroupCacheUsesLocalPosixGroup() throws Exception {
		Path baseDir    = createBaseDir();
		String posixGroup = Files.readAttributes(baseDir, PosixFileAttributes.class).group().getName();

		try {
			UserGroupInformation user = UserGroupInformation.createUserForTesting("spark_user2", new String[] {"spark_user2", posixGroup});
			user.doAs((PrivilegedExceptionAction<Void>) () -> {
				try (Session ignored = startSession(baseDir, RangerLocalDirectory.SUBDIR_MODE_PERGROUP, new ScriptedAdminClient(ScriptedAdminClient.Mode.SERVE), false)) {
					Path groupDir  = baseDir.resolve(posixGroup);
					Path cacheFile = groupDir.resolve(CACHE_FILE);

					assertTrue(Files.isDirectory(groupDir));
					assertTrue(Files.isRegularFile(cacheFile));
					assertEquals(PosixFilePermissions.fromString("rwxrwx---"), Files.getPosixFilePermissions(groupDir));
					assertEquals(PosixFilePermissions.fromString("rw-rw----"), Files.getPosixFilePermissions(cacheFile));
					assertEquals(posixGroup, Files.readAttributes(groupDir, PosixFileAttributes.class).group().getName());
				}
				return null;
			});
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testPerGroupCacheIsNotWrittenWhenGroupIsNotLocal() throws Exception {
		Path baseDir = createBaseDir();

		try {
			UserGroupInformation user = UserGroupInformation.createUserForTesting("spark_user9", new String[] {"spark_user9", "ranger_it_missing_group"});
			user.doAs((PrivilegedExceptionAction<Void>) () -> {
				try (Session session = startSession(baseDir, RangerLocalDirectory.SUBDIR_MODE_PERGROUP, new ScriptedAdminClient(ScriptedAdminClient.Mode.SERVE), false)) {
					assertFalse(Files.exists(baseDir.resolve("ranger_it_missing_group").resolve(CACHE_FILE)));
					assertEquals(POLICY_VERSION, session.plugin.getPoliciesVersion());
					assertAllowed(session.plugin);
				}
				return null;
			});
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testPerGroupPathFallsBackToPrimaryGroupWhenOnlyPrivateGroupExists() throws Exception {
		Path baseDir = createBaseDir();

		try {
			UserGroupInformation user = UserGroupInformation.createUserForTesting("spark_user2", new String[] {"spark_user2"});
			RangerLocalDirectory.ResolvedDirectory directory = user.doAs((PrivilegedExceptionAction<RangerLocalDirectory.ResolvedDirectory>) () ->
					RangerLocalDirectory.resolve(baseDir.toString(),
							RangerLocalDirectory.SUBDIR_MODE_PERGROUP,
							FileUtils.parsePermissions("755"),
							FileUtils.parsePermissions("644")));

			assertEquals("spark_user2", java.nio.file.Paths.get(directory.getPath()).getFileName().toString());
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testAuthzDeniedContinueKeepsCachedDecision() throws Exception {
		Path baseDir = createBaseDir();

		try {
			ScriptedAdminClient client = new ScriptedAdminClient(ScriptedAdminClient.Mode.SERVE);
			try (Session session = startSession(baseDir, RangerLocalDirectory.SUBDIR_MODE_DISABLED, client, false)) {
				assertAllowed(session.plugin);

				client.mode = ScriptedAdminClient.Mode.DENY_POLICIES;
				session.refresher.syncPoliciesWithAdmin(new DownloadTrigger());

				assertAllowed(session.plugin);
			}
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testAuthDeniedFailClosedDeniesAccess() throws Exception {
		Path baseDir = createBaseDir();

		try {
			ScriptedAdminClient client = new ScriptedAdminClient(ScriptedAdminClient.Mode.SERVE);
			try (Session session = startSession(baseDir, RangerLocalDirectory.SUBDIR_MODE_DISABLED, client, true)) {
				assertAllowed(session.plugin);

				client.mode = ScriptedAdminClient.Mode.DENY_POLICIES;
				session.refresher.syncPoliciesWithAdmin(new DownloadTrigger());

				assertDenied(session.plugin);
			}
		} finally {
			deleteRecursively(baseDir);
		}
	}

	@Test
	public void testRoleAuthDeniedFailClosedDeniesAccess() throws Exception {
		Path baseDir = createBaseDir();

		try {
			ScriptedAdminClient client = new ScriptedAdminClient(ScriptedAdminClient.Mode.SERVE);
			try (Session session = startSession(baseDir, RangerLocalDirectory.SUBDIR_MODE_DISABLED, client, true)) {
				assertAllowed(session.plugin);

				client.mode = ScriptedAdminClient.Mode.DENY_ROLES;
				session.refresher.syncPoliciesWithAdmin(new DownloadTrigger());

				assertDenied(session.plugin);
			}
		} finally {
			deleteRecursively(baseDir);
		}
	}

	private static Session startSession(Path baseDir, String subdirMode, ScriptedAdminClient client, boolean failClosed) throws Exception {
		RangerPolicyEngineOptions options = new RangerPolicyEngineOptions();
		options.disablePolicyRefresher    = true;
		options.disableTagRetriever       = true;
		options.disableUserStoreRetriever = true;

		RangerPluginConfig config = new RangerPluginConfig("hive", SERVICE_NAME, APP_ID, "cl1", "on-prem", options);
		String             prefix = config.getPropertyPrefix();

		config.set(prefix + ".policy.cache.dir", baseDir.toString());
		config.set(prefix + ".policy.cache.subdir.mode", subdirMode);
		config.set(prefix + ".policy.cache.dir.perms", "755");
		config.set(prefix + ".policy.cache.file.perms", "644");
		config.set(prefix + ".policy.pollIntervalMs", "3600000");
		if (failClosed) {
			config.set(prefix + ".policy.refresh.authz.denied.mode", "failclosed");
		}

		RangerBasePlugin plugin = new RangerBasePlugin(config);
		plugin.getPluginContext().setAdminClient(client);

		PolicyRefresher refresher = new PolicyRefresher(plugin);
		Field           field     = RangerBasePlugin.class.getDeclaredField("refresher");
		field.setAccessible(true);
		field.set(plugin, refresher);

		Session session = new Session(plugin, refresher);
		session.start();
		return session;
	}

	private static void assertAllowed(RangerBasePlugin plugin) {
		RangerAccessResult result = plugin.isAccessAllowed(selectRequest());

		assertNotNull(result);
		assertTrue(result.getIsAllowed());
	}

	private static void assertDenied(RangerBasePlugin plugin) {
		RangerAccessResult result = plugin.isAccessAllowed(selectRequest());

		assertNotNull(result);
		assertFalse(result.getIsAllowed());
		assertTrue(result.getIsAccessDetermined());
		assertEquals("Policy refresh authorization denied by Ranger Admin", result.getReason());
	}

	private static RangerAccessRequest selectRequest() {
		RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
		resource.setValue("database", "sales");
		resource.setValue("table", "prospects");

		RangerAccessRequestImpl request = new RangerAccessRequestImpl(resource, "select", "res-user", new HashSet<String>(), null);
		request.setAccessTime(new Date());
		return request;
	}

	private static Path createBaseDir() throws IOException {
		Path baseDir = Files.createTempDirectory("ranger-policy-cache");
		Files.setPosixFilePermissions(baseDir, PosixFilePermissions.fromString("rwxr-xr-x"));
		return baseDir;
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

	private static final class Session implements AutoCloseable {
		private final RangerBasePlugin plugin;
		private final PolicyRefresher  refresher;

		private Session(RangerBasePlugin plugin, PolicyRefresher refresher) {
			this.plugin    = plugin;
			this.refresher = refresher;
		}

		private void start() {
			refresher.setDaemon(true);
			refresher.startRefresher();
		}

		@Override
		public void close() {
			refresher.stopRefresher();
		}
	}

	private static final class ScriptedAdminClient extends AbstractRangerAdminClient {
		private enum Mode {
			SERVE,
			UNREACHABLE,
			DENY_POLICIES,
			DENY_ROLES
		}

		private final ServicePolicies policies;
		private final RangerRoles     roles;
		private       Mode            mode;

		private ScriptedAdminClient(Mode mode) throws IOException {
			this.mode     = mode;
			this.policies = loadPolicies();
			this.roles    = new RangerRoles();
			this.roles.setServiceName(SERVICE_NAME);
			this.roles.setRoleVersion(1L);
			this.roles.setRangerRoles(new HashSet<>());
		}

		@Override
		public ServicePolicies getServicePoliciesIfUpdated(long lastKnownVersion, long lastActivationTimeInMillis) throws Exception {
			if (mode == Mode.UNREACHABLE) {
				throw new IOException("connection refused");
			}
			if (mode == Mode.DENY_POLICIES) {
				throw new RangerAdminClientAccessDeniedException(403, "User doesn't have permission to download policy");
			}
			if (policies.getPolicyVersion() != null && policies.getPolicyVersion().longValue() == lastKnownVersion) {
				return null;
			}
			return policies;
		}

		@Override
		public RangerRoles getRolesIfUpdated(long lastKnownRoleVersion, long lastActivationTimeInMillis) throws Exception {
			if (mode == Mode.UNREACHABLE) {
				throw new IOException("connection refused");
			}
			if (mode == Mode.DENY_ROLES) {
				throw new RangerAdminClientAccessDeniedException(403, "User doesn't have permission to download UserGroupRoles");
			}
			if (roles.getRoleVersion() != null && roles.getRoleVersion().longValue() == lastKnownRoleVersion) {
				return null;
			}
			return roles;
		}

		private static ServicePolicies loadPolicies() throws IOException {
			try (InputStream in = PolicyRefresherCacheSubdirTest.class.getResourceAsStream("/plugin/hive_policies.json")) {
				assertNotNull(in);
				try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
					ServicePolicies policies = GSON.fromJson(reader, ServicePolicies.class);
					policies.setServiceName(SERVICE_NAME);
					policies.setPolicyVersion(POLICY_VERSION);
					policies.getServiceDef().setMarkerAccessTypes(ServiceDefUtil.getMarkerAccessTypes(policies.getServiceDef().getAccessTypes()));
					return policies;
				}
			}
		}
	}
}
