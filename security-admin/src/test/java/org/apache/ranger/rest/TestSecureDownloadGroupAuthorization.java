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

package org.apache.ranger.rest;

import org.apache.ranger.biz.AssetMgr;
import org.apache.ranger.biz.RangerBizUtil;
import org.apache.ranger.biz.RoleDBStore;
import org.apache.ranger.biz.ServiceDBStore;
import org.apache.ranger.biz.TagDBStore;
import org.apache.ranger.common.RESTErrorUtil;
import org.apache.ranger.common.ServiceUtil;
import org.apache.ranger.common.UserSessionBase;
import org.apache.ranger.db.RangerDaoManager;
import org.apache.ranger.db.XXGroupDao;
import org.apache.ranger.db.XXServiceDao;
import org.apache.ranger.db.XXServiceDefDao;
import org.apache.ranger.db.XXUserDao;
import org.apache.ranger.entity.XXGroup;
import org.apache.ranger.entity.XXPortalUser;
import org.apache.ranger.entity.XXService;
import org.apache.ranger.entity.XXServiceDef;
import org.apache.ranger.entity.XXUser;
import org.apache.ranger.plugin.model.RangerService;
import org.apache.ranger.plugin.util.RangerRoles;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.apache.ranger.plugin.util.ServiceTags;
import org.apache.ranger.security.context.RangerContextHolder;
import org.apache.ranger.security.context.RangerSecurityContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.WebApplicationException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class TestSecureDownloadGroupAuthorization {
	private static final String SERVICE_NAME = "dev_hive";

	@Mock
	private RangerDaoManager daoManager;
	@Mock
	private XXServiceDao serviceDao;
	@Mock
	private XXServiceDefDao serviceDefDao;
	@Mock
	private XXUserDao userDao;
	@Mock
	private XXGroupDao groupDao;
	@Mock
	private ServiceDBStore svcStore;
	@Mock
	private TagDBStore tagStore;
	@Mock
	private RoleDBStore roleStore;
	@Mock
	private ServiceUtil serviceUtil;
	@Mock
	private RESTErrorUtil restErrorUtil;
	@Mock
	private AssetMgr assetMgr;
	@Mock
	private HttpServletRequest request;

	private RangerBizUtil bizUtil;
	private ServiceREST   serviceREST;
	private TagREST       tagREST;
	private RoleREST      roleREST;

	@Before
	public void setup() throws Exception {
		when(daoManager.getXXService()).thenReturn(serviceDao);
		when(daoManager.getXXServiceDef()).thenReturn(serviceDefDao);
		lenient().when(daoManager.getXXUser()).thenReturn(userDao);
		lenient().when(daoManager.getXXGroup()).thenReturn(groupDao);

		XXService service = new XXService();
		service.setName(SERVICE_NAME);
		service.setType(1L);
		XXServiceDef serviceDef = new XXServiceDef();
		serviceDef.setImplclassname("org.apache.ranger.services.hive.RangerServiceHive");
		when(serviceDao.findByName(SERVICE_NAME)).thenReturn(service);
		when(serviceDefDao.getById(1L)).thenReturn(serviceDef);
		lenient().when(serviceUtil.isValidService(eq(SERVICE_NAME), any())).thenReturn(true);
		lenient().when(restErrorUtil.createRESTException(anyInt(), anyString(), anyBoolean()))
				.thenThrow(new WebApplicationException());

		bizUtil = new RangerBizUtil();
		Field daoField = RangerBizUtil.class.getDeclaredField("daoManager");
		daoField.setAccessible(true);
		daoField.set(bizUtil, daoManager);

		serviceREST = new ServiceREST();
		serviceREST.bizUtil = bizUtil;
		serviceREST.daoManager = daoManager;
		serviceREST.svcStore = svcStore;
		serviceREST.serviceUtil = serviceUtil;
		serviceREST.restErrorUtil = restErrorUtil;
		serviceREST.assetMgr = assetMgr;

		tagREST = new TagREST();
		tagREST.bizUtil = bizUtil;
		tagREST.daoManager = daoManager;
		tagREST.svcStore = svcStore;
		tagREST.tagStore = tagStore;
		tagREST.restErrorUtil = restErrorUtil;
		tagREST.assetMgr = assetMgr;

		roleREST = new RoleREST();
		roleREST.bizUtil = bizUtil;
		roleREST.daoManager = daoManager;
		roleREST.svcStore = svcStore;
		roleREST.roleStore = roleStore;
		roleREST.serviceUtil = serviceUtil;
		roleREST.restErrorUtil = restErrorUtil;
		roleREST.assetMgr = assetMgr;
	}

	@After
	public void tearDown() {
		RangerContextHolder.resetSecurityContext();
	}

	@Test
	public void testPolicyDownloadAllowedForGroupMember() throws Exception {
		login("spark_user");
		stubUser("spark_user", "spark_group");

		ServicePolicies policies = downloadPolicies(configs(ServiceREST.Allowed_Group_List_For_Download, "spark_group"));

		assertNotNull(policies);
		verify(restErrorUtil, never()).createRESTException(eq(HttpServletResponse.SC_FORBIDDEN), any(), eq(true));
	}

	@Test
	public void testPolicyDownloadDeniedWhenUserIsOutsideGroup() throws Exception {
		login("spark_user");
		stubUser("spark_user", "other_group");

		expectForbidden(() -> downloadPolicies(configs(ServiceREST.Allowed_Group_List_For_Download, "spark_group")),
				"User doesn't have permission to download policy");
	}

	@Test
	public void testTagDownloadAllowedForGroupMember() throws Exception {
		login("spark_user");
		stubUser("spark_user", "spark_group");
		ServiceTags tags = new ServiceTags();
		tags.setTagVersion(4L);
		when(svcStore.getServiceByName(SERVICE_NAME)).thenReturn(serviceWith(configs(TagREST.Allowed_Group_List_For_Tag_Download, "spark_group")));
		when(tagStore.getServiceTagsIfUpdated(eq(SERVICE_NAME), eq(1L), eq(true))).thenReturn(tags);

		ServiceTags downloaded = tagREST.getSecureServiceTagsIfUpdated(SERVICE_NAME, 1L, 0L, "plugin", false, "", request);

		assertNotNull(downloaded);
	}

	@Test
	public void testTagDownloadDeniedWhenUserIsOutsideGroup() throws Exception {
		login("spark_user");
		stubUser("spark_user", "other_group");
		when(svcStore.getServiceByName(SERVICE_NAME)).thenReturn(serviceWith(configs(TagREST.Allowed_Group_List_For_Tag_Download, "spark_group")));

		expectForbidden(() -> tagREST.getSecureServiceTagsIfUpdated(SERVICE_NAME, 1L, 0L, "plugin", false, "", request),
				"User doesn't have permission to download tags");
	}

	@Test
	public void testRoleDownloadAllowedForGroupMember() throws Exception {
		login("spark_user");
		stubUser("spark_user", "spark_group");
		RangerRoles roles = new RangerRoles();
		roles.setRoleVersion(2L);
		when(roleStore.getRoles(SERVICE_NAME, 1L)).thenReturn(roles);

		RangerRoles downloaded = downloadRoles(configs(RoleREST.POLICY_DOWNLOAD_GROUPS, "spark_group"));

		assertNotNull(downloaded);
	}

	@Test
	public void testRoleDownloadDeniedWhenUserIsOutsideGroup() throws Exception {
		login("spark_user");
		stubUser("spark_user", "other_group");

		expectForbidden(() -> downloadRoles(configs(RoleREST.POLICY_DOWNLOAD_GROUPS, "spark_group")),
				"User doesn't have permission to download UserGroupRoles");
	}

	@Test
	public void testKerberosPrincipalUsesShortNameForGroupLookup() throws Exception {
		login("spark_user6@krb5-test");
		stubUser("spark_user6", "spark_group");

		ServicePolicies policies = downloadPolicies(configs(ServiceREST.Allowed_Group_List_For_Download, "spark_group"));

		assertNotNull(policies);
		verify(userDao).findByUserName("spark_user6");
	}

	@Test
	public void testAllInAuthUsersAllowsDownload() throws Exception {
		login("spark_user");

		ServicePolicies policies = downloadPolicies(configs(ServiceREST.Allowed_User_List_For_Download, "*"));

		assertNotNull(policies);
	}

	@Test
	public void testAllInAuthGroupsDoesNotAllowDownload() throws Exception {
		login("spark_user");
		stubUser("spark_user", "spark_group");

		expectForbidden(() -> downloadPolicies(configs(ServiceREST.Allowed_Group_List_For_Download, "*")),
				"User doesn't have permission to download policy");
	}

	@Test
	public void testPublicGroupAllowsUserWhoHasAnyGroup() throws Exception {
		login("spark_user");
		stubUser("spark_user", "readers");

		ServicePolicies policies = downloadPolicies(configs(ServiceREST.Allowed_Group_List_For_Download, "public"));

		assertNotNull(policies);
	}

	@Test
	public void testPublicGroupDoesNotAllowUserWithoutGroups() throws Exception {
		login("spark_user");
		stubUser("spark_user");

		expectForbidden(() -> downloadPolicies(configs(ServiceREST.Allowed_Group_List_For_Download, "public")),
				"User doesn't have permission to download policy");
	}

	@Test
	public void testSecondConfiguredGroupIsEnough() throws Exception {
		login("spark_user");
		stubUser("spark_user", "spark_group");

		ServicePolicies policies = downloadPolicies(configs(ServiceREST.Allowed_Group_List_For_Download, "other_group,spark_group"));

		assertNotNull(policies);
	}

	@Test
	public void testGroupMatchIsCaseSensitive() throws Exception {
		login("spark_user");
		stubUser("spark_user", "Spark_Group");

		expectForbidden(() -> downloadPolicies(configs(ServiceREST.Allowed_Group_List_For_Download, "spark_group")),
				"User doesn't have permission to download policy");
	}

	private ServicePolicies downloadPolicies(Map<String, String> configs) throws Exception {
		ServicePolicies policies = new ServicePolicies();
		policies.setServiceName(SERVICE_NAME);
		policies.setPolicyVersion(3L);
		when(svcStore.getServiceByName(SERVICE_NAME)).thenReturn(serviceWith(configs));
		lenient().when(svcStore.getServicePoliciesIfUpdated(SERVICE_NAME, 1L, false)).thenReturn(policies);
		return serviceREST.getSecureServicePoliciesIfUpdated(SERVICE_NAME, 1L, 0L, "plugin", "", "", true, "", request);
	}

	private RangerRoles downloadRoles(Map<String, String> configs) throws Exception {
		when(svcStore.getServiceByName(SERVICE_NAME)).thenReturn(serviceWith(configs));
		return roleREST.getSecureRangerRolesIfUpdated(SERVICE_NAME, 1L, 0L, "plugin", "", "", request);
	}

	private void expectForbidden(DownloadAction action, String message) throws Exception {
		try {
			action.download();
			fail("expected download to be forbidden");
		} catch (WebApplicationException expected) {
			verify(restErrorUtil).createRESTException(HttpServletResponse.SC_FORBIDDEN, message, true);
		}
	}

	private void login(String loginId) {
		RangerSecurityContext context    = new RangerSecurityContext();
		UserSessionBase       session    = new UserSessionBase();
		XXPortalUser          portalUser = new XXPortalUser();

		portalUser.setLoginId(loginId);
		session.setXXPortalUser(portalUser);
		session.setUserAdmin(false);
		session.setKeyAdmin(false);
		context.setUserSession(session);
		RangerContextHolder.setSecurityContext(context);
	}

	private void stubUser(String shortName, String... groupNames) {
		XXUser user = new XXUser();
		user.setId(7L);
		user.setName(shortName);
		when(userDao.findByUserName(shortName)).thenReturn(user);

		if (groupNames.length == 0) {
			when(groupDao.findByUserId(7L)).thenReturn(Collections.emptyList());
			return;
		}

		java.util.List<XXGroup> groups = new java.util.ArrayList<>();
		for (String groupName : groupNames) {
			XXGroup group = new XXGroup();
			group.setName(groupName);
			groups.add(group);
		}
		when(groupDao.findByUserId(7L)).thenReturn(groups);
	}

	private static RangerService serviceWith(Map<String, String> configs) {
		RangerService service = new RangerService();
		service.setName(SERVICE_NAME);
		service.setConfigs(configs);
		return service;
	}

	private static Map<String, String> configs(String key, String value) {
		Map<String, String> configs = new HashMap<>();
		configs.put(key, value);
		return configs;
	}

	private interface DownloadAction {
		void download() throws Exception;
	}
}
