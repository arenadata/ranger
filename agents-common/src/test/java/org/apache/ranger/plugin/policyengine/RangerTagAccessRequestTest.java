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

package org.apache.ranger.plugin.policyengine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Collections;

import org.apache.ranger.plugin.contextenricher.RangerTagForEval;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.RangerTag;
import org.apache.ranger.plugin.policyresourcematcher.RangerPolicyResourceMatcher;
import org.apache.ranger.plugin.util.RangerAccessRequestUtil;
import org.junit.Test;

/**
 * Checks that a tag request reads the owner of the tagged resource only when a tag policy needs it:
 * some plugins look the resource owner up with a remote call.
 */
public class RangerTagAccessRequestTest {
	@Test
	public void testOwnerOfTaggedResourceIsReadLazily() {
		CountingOwnerResource resource   = new CountingOwnerResource("alice");
		RangerAccessRequest   tagRequest = createTagRequest(resource);

		assertEquals(0, resource.ownerReads);

		assertEquals("alice", tagRequest.getResource().getOwnerUser());
		assertEquals("alice", RangerAccessRequestUtil.getTokenFromContext(tagRequest.getContext(), RangerAccessRequestUtil.KEY_OWNER));
		assertEquals(2, resource.ownerReads);
	}

	@Test
	public void testEmptyOwnerLeavesOwnerTokenUnresolved() {
		RangerAccessRequest tagRequest = createTagRequest(new CountingOwnerResource(""));

		assertEquals("", tagRequest.getResource().getOwnerUser());
		assertNull(RangerAccessRequestUtil.getTokenFromContext(tagRequest.getContext(), RangerAccessRequestUtil.KEY_OWNER));
	}

	/**
	 * Creates the request for a PII tag of the given resource, as the policy engine does to evaluate tag policies.
	 */
	private static RangerAccessRequest createTagRequest(RangerAccessResourceImpl resource) {
		RangerAccessRequest request = new RangerAccessRequestImpl(resource, "select", "alice", Collections.emptySet(), Collections.emptySet());
		RangerTagForEval    tag     = new RangerTagForEval(new RangerTag("PII", Collections.emptyMap()), RangerPolicyResourceMatcher.MatchType.SELF);

		return new RangerTagAccessRequest(tag, new RangerServiceDef(), request);
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
