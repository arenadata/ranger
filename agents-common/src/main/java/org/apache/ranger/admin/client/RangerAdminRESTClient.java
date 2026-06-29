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

 package org.apache.ranger.admin.client;


import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.jersey.api.client.ClientResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.NewCookie;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.http.HttpStatus;
import org.apache.ranger.admin.client.datatype.RESTResponse;
import org.apache.ranger.plugin.util.RangerDelegationTokenIdentifier;
import org.apache.ranger.audit.provider.MiscUtil;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.utils.StringUtil;
import org.apache.ranger.plugin.model.RangerRole;
import org.apache.ranger.plugin.model.ResourceMappingDiffs;
import org.apache.ranger.plugin.util.GrantRevokeRequest;
import org.apache.ranger.plugin.util.GrantRevokeRoleRequest;
import org.apache.ranger.plugin.util.JsonUtilsV2;
import org.apache.ranger.plugin.util.RangerCommonConstants;
import org.apache.ranger.plugin.util.RangerPluginCapability;
import org.apache.ranger.plugin.util.RangerRESTClient;
import org.apache.ranger.plugin.util.RangerRESTUtils;
import org.apache.ranger.plugin.util.RangerRoles;
import org.apache.ranger.plugin.util.RangerServiceNotFoundException;
import org.apache.ranger.plugin.util.RangerUserStore;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.apache.ranger.plugin.util.ServiceTags;
import org.apache.ranger.plugin.util.URLEncoderUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RangerAdminRESTClient extends AbstractRangerAdminClient {
	private static final Logger LOG = LoggerFactory.getLogger(RangerAdminRESTClient.class);

	private static final TypeReference<List<String>> TYPE_LIST_STRING = new TypeReference<List<String>>() {};

	private String           serviceName;
    private String           serviceNameUrlParam;
	private String           pluginId;
	private String           clusterName;
	private RangerRESTClient restClient;
	private RangerRESTUtils  restUtils   = new RangerRESTUtils();
	private boolean 		 supportsPolicyDeltas;
	private boolean 		 supportsTagDeltas;
	private boolean			 isRangerCookieEnabled;
	private String           rangerAdminCookieName;
	private Cookie           sessionId            = null;
	private final String     pluginCapabilities   = Long.toHexString(new RangerPluginCapability().getPluginCapabilities());

	@Override
	public void init(String serviceName, String appId, String propertyPrefix, Configuration config) {
	    super.init(serviceName, appId, propertyPrefix, config);

		this.serviceName = serviceName;
		this.pluginId    = restUtils.getPluginId(serviceName, appId);

		String url                      = "";
		String tmpUrl                   = config.get(propertyPrefix + ".policy.rest.url");
		String sslConfigFileName 		= config.get(propertyPrefix + ".policy.rest.ssl.config.file");
		clusterName       				= config.get(propertyPrefix + ".access.cluster.name", "");
		if(StringUtil.isEmpty(clusterName)){
			clusterName =config.get(propertyPrefix + ".ambari.cluster.name", "");
			if (StringUtil.isEmpty(clusterName)) {
				if (config instanceof RangerPluginConfig) {
					clusterName = ((RangerPluginConfig)config).getClusterName();
				}
			}
		}
		int	 restClientConnTimeOutMs	= config.getInt(propertyPrefix + ".policy.rest.client.connection.timeoutMs", 120 * 1000);
		int	 restClientReadTimeOutMs	= config.getInt(propertyPrefix + ".policy.rest.client.read.timeoutMs", 30 * 1000);
		int	 restClientMaxRetryAttempts	= config.getInt(propertyPrefix + ".policy.rest.client.max.retry.attempts", 3);
		int	 restClientRetryIntervalMs	= config.getInt(propertyPrefix + ".policy.rest.client.retry.interval.ms", 1 * 1000);

		supportsPolicyDeltas            = config.getBoolean(propertyPrefix + RangerCommonConstants.PLUGIN_CONFIG_SUFFIX_POLICY_DELTA, RangerCommonConstants.PLUGIN_CONFIG_SUFFIX_POLICY_DELTA_DEFAULT);
		supportsTagDeltas               = config.getBoolean(propertyPrefix + RangerCommonConstants.PLUGIN_CONFIG_SUFFIX_TAG_DELTA, RangerCommonConstants.PLUGIN_CONFIG_SUFFIX_TAG_DELTA_DEFAULT);
		isRangerCookieEnabled			= config.getBoolean(propertyPrefix + ".policy.rest.client.cookie.enabled", RangerCommonConstants.POLICY_REST_CLIENT_SESSION_COOKIE_ENABLED);
		rangerAdminCookieName			= config.get(propertyPrefix + ".policy.rest.client.session.cookie.name", RangerCommonConstants.DEFAULT_COOKIE_NAME);

        if (!StringUtil.isEmpty(tmpUrl)) {
            url = tmpUrl.trim();
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

		init(url, sslConfigFileName, restClientConnTimeOutMs , restClientReadTimeOutMs, restClientMaxRetryAttempts, restClientRetryIntervalMs, config);

        try {
            this.serviceNameUrlParam = URLEncoderUtil.encodeURIParam(serviceName);
        } catch (UnsupportedEncodingException e) {
            LOG.warn("Unsupported encoding, serviceName=" + serviceName);
            this.serviceNameUrlParam = serviceName;
        }
	}

	@Override
	public ServicePolicies getServicePoliciesIfUpdated(final long lastKnownVersion, final long lastActivationTimeInMillis) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.getServicePoliciesIfUpdated(" + lastKnownVersion + ", " + lastActivationTimeInMillis + ")");
		}

		final ServicePolicies      ret;
		final UserGroupInformation user         = MiscUtil.getUGILoginUser();
		final boolean              isSecureMode = isKerberosEnabled(user);
		final Cookie               sessionId    = this.sessionId;
		final ClientResponse       response;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put(RangerRESTUtils.REST_PARAM_LAST_KNOWN_POLICY_VERSION, Long.toString(lastKnownVersion));
		queryParams.put(RangerRESTUtils.REST_PARAM_LAST_ACTIVATION_TIME, Long.toString(lastActivationTimeInMillis));
		queryParams.put(RangerRESTUtils.REST_PARAM_PLUGIN_ID, pluginId);
		queryParams.put(RangerRESTUtils.REST_PARAM_CLUSTER_NAME, clusterName);
		queryParams.put(RangerRESTUtils.REST_PARAM_SUPPORTS_POLICY_DELTAS, Boolean.toString(supportsPolicyDeltas));
		queryParams.put(RangerRESTUtils.REST_PARAM_CAPABILITIES, pluginCapabilities);

		String secureURL    = RangerRESTUtils.REST_URL_POLICY_GET_FOR_SECURE_SERVICE_IF_UPDATED + serviceNameUrlParam;
		String nonSecureURL = RangerRESTUtils.REST_URL_POLICY_GET_FOR_SERVICE_IF_UPDATED + serviceNameUrlParam;
		response = getWithAuth(secureURL, nonSecureURL, queryParams, user, isSecureMode, sessionId);

		checkAndResetSessionCookie(response);

		if (response == null) {
            LOG.error("Error getting policies; Received NULL response!!. secureMode={}, user={}, serviceName={}", isSecureMode, user, serviceName);
			throw new IOException("Error getting policies; received null response for serviceName=" + serviceName);
		} else if (response.getStatus() == HttpServletResponse.SC_NOT_MODIFIED || response.getStatus() == HttpServletResponse.SC_NO_CONTENT) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			if (LOG.isDebugEnabled()) {
				LOG.debug("No change in policies. secureMode=" + isSecureMode + ", user=" + user
								  + ", response=" + resp + ", serviceName=" + serviceName
								  + ", " + "lastKnownVersion=" + lastKnownVersion
								  + ", " + "lastActivationTimeInMillis=" + lastActivationTimeInMillis);
			}
			ret = null;
		} else if (response.getStatus() == HttpServletResponse.SC_OK) {
			ret = JsonUtilsV2.readResponse(response, ServicePolicies.class);
		} else if (response.getStatus() == HttpServletResponse.SC_NOT_FOUND) {
			ret = null;
			LOG.error("Error getting policies; service not found. secureMode=" + isSecureMode + ", user=" + user
							  + ", response=" + response.getStatus() + ", serviceName=" + serviceName
							  + ", " + "lastKnownVersion=" + lastKnownVersion
							  + ", " + "lastActivationTimeInMillis=" + lastActivationTimeInMillis);
			String exceptionMsg = response.hasEntity() ? response.getEntity(String.class) : null;

			RangerServiceNotFoundException.throwExceptionIfServiceNotFound(serviceName, exceptionMsg);

			LOG.warn("Received 404 error code with body:[" + exceptionMsg + "], Ignoring");
		} else if (isAccessDenied(response)) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.warn("Error getting policies. secureMode=" + isSecureMode + ", user=" + user + ", response=" + resp + ", serviceName=" + serviceName);
			throw new RangerAdminClientAccessDeniedException(response.getStatus(), resp.getMessage());
		} else {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.warn("Error getting policies. secureMode=" + isSecureMode + ", user=" + user + ", response=" + resp + ", serviceName=" + serviceName);
			throw new IOException("Error getting policies. response=" + resp + ", serviceName=" + serviceName);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.getServicePoliciesIfUpdated(" + lastKnownVersion + ", " + lastActivationTimeInMillis + "): " + ret);
		}

		return ret;
	}

	@Override
	public RangerRoles getRolesIfUpdated(final long lastKnownRoleVersion, final long lastActivationTimeInMillis) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.getRolesIfUpdated(" + lastKnownRoleVersion + ", " + lastActivationTimeInMillis + ")");
		}

		final RangerRoles ret;

		final UserGroupInformation user = MiscUtil.getUGILoginUser();
		final boolean isSecureMode      = isKerberosEnabled(user);
		final Cookie  sessionId         = this.sessionId;
		final ClientResponse response;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put(RangerRESTUtils.REST_PARAM_LAST_KNOWN_ROLE_VERSION, Long.toString(lastKnownRoleVersion));
		queryParams.put(RangerRESTUtils.REST_PARAM_LAST_ACTIVATION_TIME, Long.toString(lastActivationTimeInMillis));
		queryParams.put(RangerRESTUtils.REST_PARAM_PLUGIN_ID, pluginId);
		queryParams.put(RangerRESTUtils.REST_PARAM_CLUSTER_NAME, clusterName);
		queryParams.put(RangerRESTUtils.REST_PARAM_CAPABILITIES, pluginCapabilities);

		String secureURL    = RangerRESTUtils.REST_URL_SERVICE_SERCURE_GET_USER_GROUP_ROLES + serviceNameUrlParam;
		String nonSecureURL = RangerRESTUtils.REST_URL_SERVICE_GET_USER_GROUP_ROLES + serviceNameUrlParam;
		response = getWithAuth(secureURL, nonSecureURL, queryParams, user, isSecureMode, sessionId);

		checkAndResetSessionCookie(response);

		if (response == null) {
            LOG.error("Error getting Roles; Received NULL response!!. secureMode={}, user={}, serviceName={}", isSecureMode, user, serviceName);
			throw new IOException("Error getting Roles; received null response for serviceName=" + serviceName);
		} else if (response.getStatus() == HttpServletResponse.SC_NOT_MODIFIED || response.getStatus() == HttpServletResponse.SC_NO_CONTENT) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			if (LOG.isDebugEnabled()) {
                LOG.debug("No change in Roles. secureMode={}, user={}, response={}, serviceName={}, lastKnownRoleVersion={}, lastActivationTimeInMillis={}",
                        isSecureMode,
                        user,
                        resp,
                        serviceName,
                        lastKnownRoleVersion,
                        lastActivationTimeInMillis);
			}
			ret = null;
		} else if (response.getStatus() == HttpServletResponse.SC_OK) {
			ret = JsonUtilsV2.readResponse(response, RangerRoles.class);
		} else if (response.getStatus() == HttpServletResponse.SC_NOT_FOUND) {
			ret = null;
			LOG.error("Error getting Roles; service not found. secureMode=" + isSecureMode + ", user=" + user
							  + ", response=" + response.getStatus() + ", serviceName=" + serviceName
							  + ", " + "lastKnownRoleVersion=" + lastKnownRoleVersion
							  + ", " + "lastActivationTimeInMillis=" + lastActivationTimeInMillis);
			String exceptionMsg = response.hasEntity() ? response.getEntity(String.class) : null;

			RangerServiceNotFoundException.throwExceptionIfServiceNotFound(serviceName, exceptionMsg);

			LOG.warn("Received 404 error code with body:[" + exceptionMsg + "], Ignoring");
		} else if (isAccessDenied(response)) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.warn("Error getting Roles. secureMode=" + isSecureMode + ", user=" + user + ", response=" + resp + ", serviceName=" + serviceName);
			throw new RangerAdminClientAccessDeniedException(response.getStatus(), resp.getMessage());
		} else {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.warn("Error getting Roles. secureMode=" + isSecureMode + ", user=" + user + ", response=" + resp + ", serviceName=" + serviceName);
			throw new IOException("Error getting Roles. response=" + resp + ", serviceName=" + serviceName);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.getRolesIfUpdated(" + lastKnownRoleVersion + ", " + lastActivationTimeInMillis + "): ");
		}

		return ret;
	}

	@Override
	public RangerRole createRole(final RangerRole request) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.createRole(" + request + ")");
		}

		RangerRole ret = null;

		final ClientResponse response;
		UserGroupInformation user = MiscUtil.getUGILoginUser();
		boolean isSecureMode = isKerberosEnabled(user);
		String relativeURL = RangerRESTUtils.REST_URL_SERVICE_CREATE_ROLE;
		Cookie sessionId = this.sessionId;

		Map <String, String> queryParams = new HashMap<String, String> ();
		queryParams.put(RangerRESTUtils.SERVICE_NAME_PARAM, serviceNameUrlParam);

		if (isSecureMode) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("create role as user " + user);
			}

			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
					try {
						return restClient.post(relativeURL, queryParams, request, sessionId);
					} catch (Exception e) {
						LOG.error("Failed to get response, Error is : "+e.getMessage());
					}

					return null;
				});
		} else {
			response = restClient.post(relativeURL, queryParams, request, sessionId);
		}

		checkAndResetSessionCookie(response);

		if(response != null && response.getStatus() != HttpServletResponse.SC_OK) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.error("createRole() failed: HTTP status=" + response.getStatus() + ", message=" + resp.getMessage() + ", isSecure=" + isSecureMode + (isSecureMode ? (", user=" + user) : ""));

			if(response.getStatus()==HttpServletResponse.SC_UNAUTHORIZED) {
				throw new AccessControlException();
			}

			throw new Exception("HTTP " + response.getStatus() + " Error: " + resp.getMessage());
		} else if(response == null) {
			throw new Exception("unknown error during createRole. roleName="  + request.getName());
		} else {
			ret = JsonUtilsV2.readResponse(response, RangerRole.class);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.createRole(" + request + ")");
		}
		return ret;
	}

	@Override
	public void dropRole(final String execUser, final String roleName) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.dropRole(" + roleName + ")");
		}

		final ClientResponse response;
		UserGroupInformation user = MiscUtil.getUGILoginUser();
		boolean isSecureMode = isKerberosEnabled(user);
		Cookie sessionId = this.sessionId;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put(RangerRESTUtils.SERVICE_NAME_PARAM, serviceNameUrlParam);
		queryParams.put(RangerRESTUtils.REST_PARAM_EXEC_USER, execUser);

		String relativeURL = RangerRESTUtils.REST_URL_SERVICE_DROP_ROLE + roleName;

		if (isSecureMode) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("drop role as user " + user);
			}
			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
					try {
						return restClient.delete(relativeURL, queryParams, sessionId);
					} catch (Exception e) {
						LOG.error("Failed to get response, Error is : "+e.getMessage());
					}

					return null;
				});
		} else {
			response = restClient.delete(relativeURL, queryParams, sessionId);
		}

		checkAndResetSessionCookie(response);

		if(response == null) {
			throw new Exception("unknown error during deleteRole. roleName="  + roleName);
		} else if(response.getStatus() != HttpServletResponse.SC_OK && response.getStatus() != HttpServletResponse.SC_NO_CONTENT) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.error("createRole() failed: HTTP status=" + response.getStatus() + ", message=" + resp.getMessage() + ", isSecure=" + isSecureMode + (isSecureMode ? (", user=" + user) : ""));

			if(response.getStatus()==HttpServletResponse.SC_UNAUTHORIZED) {
				throw new AccessControlException();
			}

			throw new Exception("HTTP " + response.getStatus() + " Error: " + resp.getMessage());
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.deleteRole(" + roleName + ")");
		}
	}

	@Override
	public List<String> getUserRoles(final String execUser) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.getUserRoles(" + execUser + ")");
		}

		List<String> ret = null;
		String emptyString = "";
		final ClientResponse response;
		UserGroupInformation user = MiscUtil.getUGILoginUser();
		boolean isSecureMode = isKerberosEnabled(user);
		String relativeURL = RangerRESTUtils.REST_URL_SERVICE_GET_USER_ROLES + execUser;
		Cookie sessionId = this.sessionId;

		if (isSecureMode) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("get roles as user " + user);
			}
			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
				try {
					return restClient.get(relativeURL, null, sessionId);
				} catch (Exception e) {
					LOG.error("Failed to get response, Error is : "+e.getMessage());
				}

				return null;
			});
		} else {
			response = restClient.get(relativeURL, null, sessionId);
		}

		checkAndResetSessionCookie(response);

		if(response != null) {
			if (response.getStatus() != HttpServletResponse.SC_OK) {
				RESTResponse resp = RESTResponse.fromClientResponse(response);
				LOG.error("getUserRoles() failed: HTTP status=" + response.getStatus() + ", message=" + resp.getMessage() + ", isSecure=" + isSecureMode + (isSecureMode ? (", user=" + user) : ""));

				if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
					throw new AccessControlException();
				}

				throw new Exception("HTTP " + response.getStatus() + " Error: " + resp.getMessage());
			} else {
				ret = JsonUtilsV2.readResponse(response, TYPE_LIST_STRING);
			}
		} else {
			throw new Exception("unknown error during getUserRoles. execUser="  + execUser);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.getUserRoles(" + execUser + ")");
		}
		return ret;
	}

	@Override
	public List<String> getAllRoles(final String execUser) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.getAllRoles()");
		}

		List<String> ret = null;
		String emptyString = "";
		final ClientResponse response;
		UserGroupInformation user = MiscUtil.getUGILoginUser();
		boolean isSecureMode = isKerberosEnabled(user);
		String relativeURL = RangerRESTUtils.REST_URL_SERVICE_GET_ALL_ROLES;
		Cookie sessionId = this.sessionId;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put(RangerRESTUtils.SERVICE_NAME_PARAM, serviceNameUrlParam);
		queryParams.put(RangerRESTUtils.REST_PARAM_EXEC_USER, execUser);

		if (isSecureMode) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("get roles as user " + user);
			}
			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
				try {
					return restClient.get(relativeURL, queryParams, sessionId);
				} catch (Exception e) {
					LOG.error("Failed to get response, Error is : "+e.getMessage());
				}

				return null;
			});
		} else {
			response = restClient.get(relativeURL, queryParams, sessionId);
		}

		checkAndResetSessionCookie(response);

		if(response != null) {
			if (response.getStatus() != HttpServletResponse.SC_OK) {
				RESTResponse resp = RESTResponse.fromClientResponse(response);
				LOG.error("getAllRoles() failed: HTTP status=" + response.getStatus() + ", message=" + resp.getMessage() + ", isSecure=" + isSecureMode + (isSecureMode ? (", user=" + user) : ""));

				if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
					throw new AccessControlException();
				}

				throw new Exception("HTTP " + response.getStatus() + " Error: " + resp.getMessage());
			} else {
				ret = JsonUtilsV2.readResponse(response, TYPE_LIST_STRING);
			}
		} else {
			throw new Exception("unknown error during getAllRoles.");
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.getAllRoles()");
		}
		return ret;
	}

	@Override
	public RangerRole getRole(final String execUser, final String roleName) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.getPrincipalsForRole(" + roleName + ")");
		}

		RangerRole ret = null;
		final ClientResponse response;
		UserGroupInformation user = MiscUtil.getUGILoginUser();
		boolean isSecureMode = isKerberosEnabled(user);
		String relativeURL = RangerRESTUtils.REST_URL_SERVICE_GET_ROLE_INFO + roleName;
		Cookie sessionId = this.sessionId;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put(RangerRESTUtils.SERVICE_NAME_PARAM, serviceNameUrlParam);
		queryParams.put(RangerRESTUtils.REST_PARAM_EXEC_USER, execUser);

		if (isSecureMode) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("get role info as user " + user);
			}
			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
				try {
					return restClient.get(relativeURL, queryParams, sessionId);
				} catch (Exception e) {
					LOG.error("Failed to get response, Error is : "+e.getMessage());
				}

				return null;
			});
		} else {
			response = restClient.get(relativeURL, queryParams, sessionId);
		}

		checkAndResetSessionCookie(response);

		if(response != null) {
			if (response.getStatus() != HttpServletResponse.SC_OK) {
				RESTResponse resp = RESTResponse.fromClientResponse(response);
				LOG.error("getPrincipalsForRole() failed: HTTP status=" + response.getStatus() + ", message=" + resp.getMessage() + ", isSecure=" + isSecureMode + (isSecureMode ? (", user=" + user) : ""));

				if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
					throw new AccessControlException();
				}

				throw new Exception("HTTP " + response.getStatus() + " Error: " + resp.getMessage());
			} else {
				ret = JsonUtilsV2.readResponse(response, RangerRole.class);
			}
		} else {
			throw new Exception("unknown error during getPrincipalsForRole. roleName="  + roleName);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.getPrincipalsForRole(" + roleName + ")");
		}
		return ret;
	}


	@Override
	public void grantRole(final GrantRevokeRoleRequest request) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.grantRole(" + request + ")");
		}

		final ClientResponse response;
		UserGroupInformation user = MiscUtil.getUGILoginUser();
		boolean isSecureMode = isKerberosEnabled(user);
		String relativeURL = RangerRESTUtils.REST_URL_SERVICE_GRANT_ROLE + serviceNameUrlParam;
		Cookie sessionId = this.sessionId;

		if (isSecureMode) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("grant role as user " + user);
			}
			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
				try {
					return restClient.put(relativeURL, request, sessionId);
				} catch (Exception e) {
					LOG.error("Failed to get response, Error is : "+e.getMessage());
				}

				return null;
			});
		} else {
			response = restClient.put(relativeURL, request, sessionId);
		}

		checkAndResetSessionCookie(response);

		if(response != null && response.getStatus() != HttpServletResponse.SC_OK) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.error("grantRole() failed: HTTP status=" + response.getStatus() + ", message=" + resp.getMessage() + ", isSecure=" + isSecureMode + (isSecureMode ? (", user=" + user) : ""));

			if(response.getStatus()==HttpServletResponse.SC_UNAUTHORIZED) {
				throw new AccessControlException();
			}

			throw new Exception("HTTP " + response.getStatus() + " Error: " + resp.getMessage());
		} else if(response == null) {
			throw new Exception("unknown error during grantRole. serviceName="  + serviceName);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.grantRole(" + request + ")");
		}
	}

	@Override
	public void revokeRole(final GrantRevokeRoleRequest request) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.revokeRole(" + request + ")");
		}

		final ClientResponse response;
		UserGroupInformation user = MiscUtil.getUGILoginUser();
		boolean isSecureMode = isKerberosEnabled(user);
		String relativeURL = RangerRESTUtils.REST_URL_SERVICE_REVOKE_ROLE + serviceNameUrlParam;
		Cookie sessionId = this.sessionId;

		if (isSecureMode) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("revoke role as user " + user);
			}
			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
				try {
					return restClient.put(relativeURL, request, sessionId);
				} catch (Exception e) {
					LOG.error("Failed to get response, Error is : "+e.getMessage());
				}

				return null;
			});
		} else {
			response = restClient.put(relativeURL, request, sessionId);
		}

		checkAndResetSessionCookie(response);

		if(response != null && response.getStatus() != HttpServletResponse.SC_OK) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.error("revokeRole() failed: HTTP status=" + response.getStatus() + ", message=" + resp.getMessage() + ", isSecure=" + isSecureMode + (isSecureMode ? (", user=" + user) : ""));

			if(response.getStatus()==HttpServletResponse.SC_UNAUTHORIZED) {
				throw new AccessControlException();
			}

			throw new Exception("HTTP " + response.getStatus() + " Error: " + resp.getMessage());
		} else if(response == null) {
			throw new Exception("unknown error during revokeRole. serviceName="  + serviceName);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.revokeRole(" + request + ")");
		}
	}

	@Override
	public void grantAccess(final GrantRevokeRequest request) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.grantAccess(" + request + ")");
		}

		final ClientResponse response;
		UserGroupInformation user = MiscUtil.getUGILoginUser();
		boolean isSecureMode = isKerberosEnabled(user);
		Cookie sessionId = this.sessionId;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put(RangerRESTUtils.REST_PARAM_PLUGIN_ID, pluginId);

		if (isSecureMode) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("grantAccess as user " + user);
			}
			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
				try {
					String relativeURL = RangerRESTUtils.REST_URL_SECURE_SERVICE_GRANT_ACCESS + serviceNameUrlParam;

					return restClient.post(relativeURL, queryParams, request, sessionId);
				} catch (Exception e) {
					LOG.error("Failed to get response, Error is : "+e.getMessage());
				}

				return null;
			});
		} else {
			String relativeURL = RangerRESTUtils.REST_URL_SERVICE_GRANT_ACCESS + serviceNameUrlParam;
			response = restClient.post(relativeURL, queryParams, request, sessionId);
		}

		checkAndResetSessionCookie(response);

		if(response != null && response.getStatus() != HttpServletResponse.SC_OK) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.error("grantAccess() failed: HTTP status=" + response.getStatus() + ", message=" + resp.getMessage() + ", isSecure=" + isSecureMode + (isSecureMode ? (", user=" + user) : ""));

			if(response.getStatus()==HttpServletResponse.SC_UNAUTHORIZED) {
				throw new AccessControlException();
			}

			throw new Exception("HTTP " + response.getStatus() + " Error: " + resp.getMessage());
		} else if(response == null) {
			throw new Exception("unknown error during grantAccess. serviceName="  + serviceName);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.grantAccess(" + request + ")");
		}
	}

	@Override
	public void revokeAccess(final GrantRevokeRequest request) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.revokeAccess(" + request + ")");
		}

		final ClientResponse response;
		UserGroupInformation user = MiscUtil.getUGILoginUser();
		boolean isSecureMode = isKerberosEnabled(user);
		Cookie sessionId = this.sessionId;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put(RangerRESTUtils.REST_PARAM_PLUGIN_ID, pluginId);

		if (isSecureMode) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("revokeAccess as user " + user);
			}
			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
				try {
					String relativeURL = RangerRESTUtils.REST_URL_SECURE_SERVICE_REVOKE_ACCESS + serviceNameUrlParam;

					return restClient.post(relativeURL, queryParams, request, sessionId);
				} catch (Exception e) {
					LOG.error("Failed to get response, Error is : "+e.getMessage());
				}

				return null;
			});
		} else {
			String relativeURL = RangerRESTUtils.REST_URL_SERVICE_REVOKE_ACCESS + serviceNameUrlParam;
			response = restClient.post(relativeURL, queryParams, request, sessionId);
		}

		checkAndResetSessionCookie(response);

		if(response != null && response.getStatus() != HttpServletResponse.SC_OK) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.error("revokeAccess() failed: HTTP status=" + response.getStatus() + ", message=" + resp.getMessage() + ", isSecure=" + isSecureMode + (isSecureMode ? (", user=" + user) : ""));

			if(response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
				throw new AccessControlException();
			}

			throw new Exception("HTTP " + response.getStatus() + " Error: " + resp.getMessage());
		} else if(response == null) {
			throw new Exception("unknown error. revokeAccess(). serviceName=" + serviceName);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.revokeAccess(" + request + ")");
		}
	}

	private void init(String url, String sslConfigFileName, int restClientConnTimeOutMs , int restClientReadTimeOutMs, int restClientMaxRetryAttempts, int restClientRetryIntervalMs, Configuration config) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.init(" + url + ", " + sslConfigFileName + ")");
		}

		restClient = new RangerRESTClient(url, sslConfigFileName, config);
		restClient.setRestClientConnTimeOutMs(restClientConnTimeOutMs);
		restClient.setRestClientReadTimeOutMs(restClientReadTimeOutMs);
		restClient.setMaxRetryAttempts(restClientMaxRetryAttempts);
		restClient.setRetryIntervalMs(restClientRetryIntervalMs);

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.init(" + url + ", " + sslConfigFileName + ")");
		}
	}

	@Override
	public ServiceTags getServiceTagsIfUpdated(final long lastKnownVersion, final long lastActivationTimeInMillis) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.getServiceTagsIfUpdated(" + lastKnownVersion + ", " + lastActivationTimeInMillis + "): ");
		}

		final ServiceTags ret;

		final UserGroupInformation user = MiscUtil.getUGILoginUser();
		final boolean isSecureMode = isKerberosEnabled(user);
		final ClientResponse response;
		final Cookie sessionId = this.sessionId;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put(RangerRESTUtils.LAST_KNOWN_TAG_VERSION_PARAM, Long.toString(lastKnownVersion));
		queryParams.put(RangerRESTUtils.REST_PARAM_LAST_ACTIVATION_TIME, Long.toString(lastActivationTimeInMillis));
		queryParams.put(RangerRESTUtils.REST_PARAM_PLUGIN_ID, pluginId);
		queryParams.put(RangerRESTUtils.REST_PARAM_SUPPORTS_TAG_DELTAS, Boolean.toString(supportsTagDeltas));
		queryParams.put(RangerRESTUtils.REST_PARAM_CAPABILITIES, pluginCapabilities);

		String secureURL    = RangerRESTUtils.REST_URL_GET_SECURE_SERVICE_TAGS_IF_UPDATED + serviceNameUrlParam;
		String nonSecureURL = RangerRESTUtils.REST_URL_GET_SERVICE_TAGS_IF_UPDATED + serviceNameUrlParam;
		response = getWithAuth(secureURL, nonSecureURL, queryParams, user, isSecureMode, sessionId);

		checkAndResetSessionCookie(response);

		if (response == null) {
            LOG.error("Error getting tags; Received NULL response!!. secureMode={}, user={}, serviceName={}", isSecureMode, user, serviceName);
			throw new IOException("Error getting tags; received null response for serviceName=" + serviceName);
		} else if (response.getStatus() == HttpServletResponse.SC_NOT_MODIFIED) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			if (LOG.isDebugEnabled()) {
                LOG.debug("No change in tags. secureMode={}, user={}, response={}, serviceName={}, lastKnownVersion={}, lastActivationTimeInMillis={}",
                        isSecureMode,
                        user,
                        resp,
                        serviceName,
                        lastKnownVersion,
                        lastActivationTimeInMillis);
			}
			ret = null;
		} else if (response.getStatus() == HttpServletResponse.SC_OK) {
			ret = JsonUtilsV2.readResponse(response, ServiceTags.class);
		} else if (response.getStatus() == HttpServletResponse.SC_NOT_FOUND) {
			ret = null;
			LOG.error("Error getting tags; service not found. secureMode=" + isSecureMode + ", user=" + user
							  + ", response=" + response.getStatus() + ", serviceName=" + serviceName
							  + ", " + "lastKnownVersion=" + lastKnownVersion
							  + ", " + "lastActivationTimeInMillis=" + lastActivationTimeInMillis);

			String exceptionMsg = response.hasEntity() ? response.getEntity(String.class) : null;
			RangerServiceNotFoundException.throwExceptionIfServiceNotFound(serviceName, exceptionMsg);
			LOG.warn("Received 404 error code with body:[" + exceptionMsg + "], Ignoring");
		} else if (isAccessDenied(response)) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.warn("Error getting tags. secureMode=" + isSecureMode + ", user=" + user + ", response=" + resp + ", serviceName=" + serviceName);
			throw new RangerAdminClientAccessDeniedException(response.getStatus(), resp.getMessage());
		} else {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.warn("Error getting tags. secureMode=" + isSecureMode + ", user=" + user + ", response=" + resp + ", serviceName=" + serviceName);
			throw new IOException("Error getting tags. response=" + resp + ", serviceName=" + serviceName);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.getServiceTagsIfUpdated(" + lastKnownVersion + ", " + lastActivationTimeInMillis + "): ");
		}

		return ret;
	}

	@Override
	public List<String> getTagTypes(String pattern) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.getTagTypes(" + pattern + "): ");
		}

		List<String> ret = null;
		String emptyString = "";
		UserGroupInformation user = MiscUtil.getUGILoginUser();
		boolean isSecureMode = isKerberosEnabled(user);
		Cookie sessionId = this.sessionId;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put(RangerRESTUtils.SERVICE_NAME_PARAM, serviceNameUrlParam);
		queryParams.put(RangerRESTUtils.PATTERN_PARAM, pattern);
		String relativeURL = RangerRESTUtils.REST_URL_LOOKUP_TAG_NAMES;

		final ClientResponse response;
		if (isSecureMode) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("getTagTypes as user " + user);
			}
			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
				try {
					return restClient.get(relativeURL, queryParams, sessionId);
				} catch (Exception e) {
					LOG.error("Failed to get response, Error is : "+e.getMessage());
				}

				return null;
			});
		} else {
			response = restClient.get(relativeURL, queryParams, sessionId);
		}

		checkAndResetSessionCookie(response);

		if(response != null && response.getStatus() == HttpServletResponse.SC_OK) {
			ret = JsonUtilsV2.readResponse(response, TYPE_LIST_STRING);
		} else {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.error("Error getting tags. response=" + resp + ", serviceName=" + serviceName + ", " + "pattern=" + pattern);
			throw new Exception(resp.getMessage());
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.getTagTypes(" + pattern + "): " + ret);
		}

		return ret;
	}

	@Override
	public RangerUserStore getUserStoreIfUpdated(long lastKnownUserStoreVersion, long lastActivationTimeInMillis) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.getUserStoreIfUpdated(" + lastKnownUserStoreVersion + ", " + lastActivationTimeInMillis + ")");
		}

		final RangerUserStore ret;
		final UserGroupInformation user = MiscUtil.getUGILoginUser();
		final boolean isSecureMode = isKerberosEnabled(user);
		final ClientResponse response;
		final Cookie sessionId = this.sessionId;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put(RangerRESTUtils.REST_PARAM_LAST_KNOWN_USERSTORE_VERSION, Long.toString(lastKnownUserStoreVersion));
		queryParams.put(RangerRESTUtils.REST_PARAM_LAST_ACTIVATION_TIME, Long.toString(lastActivationTimeInMillis));
		queryParams.put(RangerRESTUtils.REST_PARAM_PLUGIN_ID, pluginId);
		queryParams.put(RangerRESTUtils.REST_PARAM_CLUSTER_NAME, clusterName);
		queryParams.put(RangerRESTUtils.REST_PARAM_CAPABILITIES, pluginCapabilities);

		String secureURL    = RangerRESTUtils.REST_URL_SERVICE_SERCURE_GET_USERSTORE + serviceNameUrlParam;
		String nonSecureURL = RangerRESTUtils.REST_URL_SERVICE_GET_USERSTORE + serviceNameUrlParam;
		response = getWithAuth(secureURL, nonSecureURL, queryParams, user, isSecureMode, sessionId);

		checkAndResetSessionCookie(response);

		if (response == null || response.getStatus() == HttpServletResponse.SC_NOT_MODIFIED) {
			if (response == null) {
				LOG.error("Error getting UserStore; Received NULL response!!. secureMode=" + isSecureMode + ", user=" + user + ", serviceName=" + serviceName);
			} else {
				RESTResponse resp = RESTResponse.fromClientResponse(response);
				if (LOG.isDebugEnabled()) {
					LOG.debug("No change in UserStore. secureMode=" + isSecureMode + ", user=" + user
							+ ", response=" + resp + ", serviceName=" + serviceName
							+ ", " + "lastKnownUserStoreVersion=" + lastKnownUserStoreVersion
							+ ", " + "lastActivationTimeInMillis=" + lastActivationTimeInMillis);
				}
			}
			ret = null;
		} else if (response.getStatus() == HttpServletResponse.SC_OK) {
			ret = JsonUtilsV2.readResponse(response, RangerUserStore.class);
		} else if (response.getStatus() == HttpServletResponse.SC_NOT_FOUND) {
			ret = null;
			LOG.error("Error getting UserStore; service not found. secureMode=" + isSecureMode + ", user=" + user
					+ ", response=" + response.getStatus() + ", serviceName=" + serviceName
					+ ", " + "lastKnownUserStoreVersion=" + lastKnownUserStoreVersion
					+ ", " + "lastActivationTimeInMillis=" + lastActivationTimeInMillis);
			String exceptionMsg = response.hasEntity() ? response.getEntity(String.class) : null;

			RangerServiceNotFoundException.throwExceptionIfServiceNotFound(serviceName, exceptionMsg);

			LOG.warn("Received 404 error code with body:[" + exceptionMsg + "], Ignoring");
		} else {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.warn("Error getting UserStore. secureMode=" + isSecureMode + ", user=" + user + ", response=" + resp + ", serviceName=" + serviceName);
			ret = null;
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.getUserStoreIfUpdated(" + lastKnownUserStoreVersion + ", " + lastActivationTimeInMillis + "): ");
		}

		return ret;
	}

	@Override
	public ResourceMappingDiffs getResourceMappingDiffs(String sourceService, String targetService, Long diffId) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.getResourceMappingDiffs({}, {}, {})", sourceService, targetService, diffId);
		}

		final UserGroupInformation user = MiscUtil.getUGILoginUser();
		final boolean isSecureMode = isKerberosEnabled(user);
		final Cookie sessionId = this.sessionId;

		Map<String, String> queryParams = new HashMap<>();
		if (diffId != null) {
			queryParams.put(RangerRESTUtils.REST_PARAM_DIFF_ID, String.valueOf(diffId));
		}
		String relativeURL = String.format("/service/resource-mappings/%s/%s/diffs/new", sourceService, targetService);

		final ClientResponse response = getWithAuth(relativeURL, relativeURL, queryParams, user, isSecureMode, sessionId);

		checkAndResetSessionCookie(response);

		ResourceMappingDiffs diffs;
		if (response != null && response.getStatus() == HttpServletResponse.SC_OK) {
			diffs = JsonUtilsV2.readResponse(response, ResourceMappingDiffs.class);
		} else {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			LOG.error("Error getting resource mappings. Response={}", resp);
			throw new Exception(resp.getMessage());
		}

		if(LOG.isDebugEnabled()) {
            LOG.debug("<== RangerAdminRESTClient.getResourceMappingDiffs({}, {}, {})", sourceService, targetService, diffId);
		}

		return diffs;
	}

	@Override
	public Token<RangerDelegationTokenIdentifier> getDelegationToken(final String renewer) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.getDelegationToken(renewer=" + renewer + ")");
		}

		final UserGroupInformation user = MiscUtil.getUGILoginUser();
		final boolean isSecureMode = isKerberosEnabled(user);

		if (!isSecureMode) {
			throw new UnsupportedOperationException("Delegation tokens require Kerberos authentication");
		}

		final Cookie sessionId = this.sessionId;

		Map<String, String> queryParams = new HashMap<String, String>();
		if (renewer != null) {
			queryParams.put(RangerRESTUtils.REST_PARAM_RENEWER, renewer);
		}

		final ClientResponse response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
			try {
				return restClient.get(RangerRESTUtils.REST_URL_DELEGATION_TOKEN, queryParams, sessionId);
			} catch (Exception e) {
				LOG.error("Failed to get delegation token", e);
			}
			return null;
		});

		checkAndResetSessionCookie(response);

		if (response != null && response.getStatus() == HttpServletResponse.SC_OK) {
			@SuppressWarnings("unchecked")
			Map<String, String> result = JsonUtilsV2.readResponse(response, Map.class);
			String tokenEncoded = result.get("urlString");
			Token<RangerDelegationTokenIdentifier> token = new Token<>();
			token.decodeFromUrlString(tokenEncoded);
			token.setService(new Text(restClient.getUrl()));

			if (LOG.isDebugEnabled()) {
				LOG.debug("<== RangerAdminRESTClient.getDelegationToken(): token obtained, service={}", restClient.getUrl());
			}
			return token;
		} else {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			throw new Exception("Failed to get delegation token: " + (resp != null ? resp.getMessage() : "null response"));
		}
	}

	@Override
	public long renewDelegationToken(final Token<RangerDelegationTokenIdentifier> token) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.renewDelegationToken()");
		}

		final UserGroupInformation user = MiscUtil.getUGILoginUser();
		final boolean isSecureMode = isKerberosEnabled(user);
		final Cookie sessionId = this.sessionId;

		final Map<String, String> requestBody = new HashMap<>();
		requestBody.put("token", token.encodeToUrlString());

		final ClientResponse response;
		if (isSecureMode) {
			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
				try {
					return restClient.put(RangerRESTUtils.REST_URL_DELEGATION_TOKEN_RENEW, (Object) requestBody, sessionId);
				} catch (Exception e) {
					LOG.error("Failed to renew delegation token", e);
				}
				return null;
			});
		} else {
			response = restClient.put(RangerRESTUtils.REST_URL_DELEGATION_TOKEN_RENEW, (Object) requestBody, sessionId);
		}

		checkAndResetSessionCookie(response);

		if (response != null && response.getStatus() == HttpServletResponse.SC_OK) {
			@SuppressWarnings("unchecked")
			Map<String, Object> result = JsonUtilsV2.readResponse(response, Map.class);
			Number expiryTime = (Number) result.get("expirationTime");

			if (LOG.isDebugEnabled()) {
				LOG.debug("<== RangerAdminRESTClient.renewDelegationToken(): newExpiry={}", expiryTime);
			}
			return expiryTime.longValue();
		} else {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			throw new Exception("Failed to renew delegation token: " + (resp != null ? resp.getMessage() : "null response"));
		}
	}

	@Override
	public void cancelDelegationToken(final Token<RangerDelegationTokenIdentifier> token) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerAdminRESTClient.cancelDelegationToken()");
		}

		final UserGroupInformation user = MiscUtil.getUGILoginUser();
		final boolean isSecureMode = isKerberosEnabled(user);
		final Cookie sessionId = this.sessionId;

		final Map<String, String> requestBody = new HashMap<>();
		requestBody.put("token", token.encodeToUrlString());

		final ClientResponse response;
		if (isSecureMode) {
			response = MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
				try {
					return restClient.put(RangerRESTUtils.REST_URL_DELEGATION_TOKEN_CANCEL, (Object) requestBody, sessionId);
				} catch (Exception e) {
					LOG.error("Failed to cancel delegation token", e);
				}
				return null;
			});
		} else {
			response = restClient.put(RangerRESTUtils.REST_URL_DELEGATION_TOKEN_CANCEL, (Object) requestBody, sessionId);
		}

		checkAndResetSessionCookie(response);

		if (response == null || (response.getStatus() != HttpServletResponse.SC_OK && response.getStatus() != HttpServletResponse.SC_NO_CONTENT)) {
			RESTResponse resp = RESTResponse.fromClientResponse(response);
			throw new Exception("Failed to cancel delegation token: " + (resp != null ? resp.getMessage() : "null response"));
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== RangerAdminRESTClient.cancelDelegationToken()");
		}
	}

	private ClientResponse getWithAuth(String secureRelativeURL, String nonSecureRelativeURL,
									   Map<String, String> queryParams,
									   UserGroupInformation user, boolean isSecureMode,
									   Cookie sessionId) throws Exception {
		Token<RangerDelegationTokenIdentifier> delegationToken = getDelegationTokenFromUGI();

		if (delegationToken != null) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Using delegation token for Ranger auth");
			}
			Map<String, String> headers = new HashMap<>();
			headers.put(RangerRESTUtils.HEADER_DELEGATION_TOKEN, delegationToken.encodeToUrlString());
			ClientResponse response = restClient.get(secureRelativeURL, queryParams, sessionId, headers);

			if (response != null && response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED && isSecureMode) {
				LOG.warn("Delegation token auth failed (HTTP 401). Falling back to Kerberos/SPNEGO");

				response = getWithKerberos(secureRelativeURL, queryParams, sessionId);
			}

			return response;
		} else if (isSecureMode) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Using Kerberos auth as user: " + user);
			}
			return getWithKerberos(secureRelativeURL, queryParams, sessionId);
		} else {
			return restClient.get(nonSecureRelativeURL, queryParams, sessionId);
		}
	}

	private ClientResponse getWithKerberos(String relativeURL, Map<String, String> queryParams,
										   Cookie sessionId) throws Exception {
		return MiscUtil.executePrivilegedAction((PrivilegedExceptionAction<ClientResponse>) () -> {
			try {
				return restClient.get(relativeURL, queryParams, sessionId);
			} catch (Exception e) {
				LOG.error("Kerberos/SPNEGO auth failed: " + e.getMessage());
			}
			return null;
		});
	}

	private boolean isAccessDenied(ClientResponse response) {
		int status = response == null ? 0 : response.getStatus();

		return status == HttpServletResponse.SC_UNAUTHORIZED || status == HttpServletResponse.SC_FORBIDDEN;
	}

	private void checkAndResetSessionCookie(ClientResponse response) {
		if (isRangerCookieEnabled) {
			if (response == null) {
				LOG.debug("checkAndResetSessionCookie(): RESETTING sessionId - response is null");

				sessionId = null;
			} else {
				int status = response.getStatus();

				if (status == HttpStatus.SC_OK || status == HttpStatus.SC_NO_CONTENT || status == HttpStatus.SC_NOT_MODIFIED) {
					Cookie newCookie = null;

					for (NewCookie cookie : response.getCookies()) {
						if (cookie.getName().equalsIgnoreCase(rangerAdminCookieName)) {
							newCookie = cookie;

							break;
						}
					}

					if (sessionId == null || newCookie != null) {
						LOG.debug("checkAndResetSessionCookie(): status={}, sessionIdCookie={}, newCookie={}", status, sessionId, newCookie);

						sessionId = newCookie;
					}
				} else {
					LOG.debug("checkAndResetSessionCookie(): RESETTING sessionId - status={}", status);

					sessionId = null;
				}
			}
		}
	}
}
