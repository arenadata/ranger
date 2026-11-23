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

package org.apache.ranger.plugin.policyevaluator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerAccessTypeDef;
import org.apache.ranger.plugin.model.validation.RangerServiceDefHelper;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngine;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngineOptions;
import org.junit.Test;

/**
 * Checks that a policy item reads the resource owner only when it grants access to {OWNER}:
 * some plugins resolve the owner lazily with a remote call.
 */
public class RangerDefaultPolicyItemEvaluatorTest {
	@Test
	public void testOwnerIsReadOnlyForOwnerMacroItem() {
		RangerPolicyItemEvaluator evaluator = createEvaluator("alice");
		CountingOwnerResource resource = new CountingOwnerResource("alice");

		assertTrue(evaluator.isMatch(createRequest(resource, "alice")));
		assertFalse(evaluator.isMatch(createRequest(resource, "bob")));
		assertEquals(0, resource.ownerReads);

		RangerPolicyItemEvaluator ownerEvaluator = createEvaluator(RangerPolicyEngine.RESOURCE_OWNER);
		CountingOwnerResource ownedResource = new CountingOwnerResource("alice");

		assertTrue(ownerEvaluator.isMatch(createRequest(ownedResource, "alice")));
		assertTrue(ownedResource.ownerReads > 0);
	}

	/**
	 * Creates an initialized allow item evaluator that grants select to the given user.
	 */
	private static RangerPolicyItemEvaluator createEvaluator(String user) {
		RangerServiceDef serviceDef = new RangerServiceDef();
		serviceDef.setAccessTypes(Collections.singletonList(new RangerAccessTypeDef("select")));

		RangerPolicy policy = new RangerPolicy();
		policy.setId(1L);

		RangerPolicyItem policyItem = new RangerPolicyItem(Collections.singletonList(new RangerPolicyItemAccess("select")), Collections.singletonList(user), null, null, null, false);

		RangerPolicyEngineOptions options = mock(RangerPolicyEngineOptions.class);
		when(options.getServiceDefHelper()).thenReturn(mock(RangerServiceDefHelper.class));

		RangerDefaultPolicyItemEvaluator evaluator = new RangerDefaultPolicyItemEvaluator(serviceDef, policy, policyItem, RangerPolicyItemEvaluator.POLICY_ITEM_TYPE_ALLOW, 0, options);
		evaluator.init();

		return evaluator;
	}

	/**
	 * Creates a select request of the given user without groups and roles.
	 */
	private static RangerAccessRequest createRequest(RangerAccessResourceImpl resource, String user) {
		return new RangerAccessRequestImpl(resource, "select", user, Collections.emptySet(), Collections.emptySet());
	}

	/**
	 * Resource that counts how many times its owner is read.
	 */
	private static final class CountingOwnerResource extends RangerAccessResourceImpl {
		private final String owner;
		private int ownerReads;

		CountingOwnerResource(String owner) {
			this.owner = owner;
		}

		@Override
		public String getOwnerUser() {
			ownerReads++;

			return owner;
		}
	}
}
