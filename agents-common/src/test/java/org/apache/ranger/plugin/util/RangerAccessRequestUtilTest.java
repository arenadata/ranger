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

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.junit.Test;

/**
 * Checks the request context tokens of {@link RangerAccessRequestUtil} used by policy resource values.
 */
public class RangerAccessRequestUtilTest {
	private static final StringTokenReplacer TOKEN_REPLACER = new StringTokenReplacer('{', '}', '\\', "");

	@Test
	public void testOwnerIsReadOnlyWhenTokenIsUsed() {
		CountingOwnerResource resource = new CountingOwnerResource("alice");
		Map<String, Object> context = new HashMap<>();

		RangerAccessRequestUtil.setOwnerInContext(context, resource);

		assertEquals(0, resource.ownerReads);
		assertEquals("/home/user1", TOKEN_REPLACER.replaceTokens("/home/user1", context));
		assertEquals(0, resource.ownerReads);
		assertEquals("/home/alice", TOKEN_REPLACER.replaceTokens("/home/{OWNER}", context));
		assertEquals(1, resource.ownerReads);
	}

	@Test
	public void testEmptyOwnerLeavesTokenUnchanged() {
		Map<String, Object> context = new HashMap<>();

		RangerAccessRequestUtil.setOwnerInContext(context, new CountingOwnerResource(""));

		assertEquals("/home/{OWNER}", TOKEN_REPLACER.replaceTokens("/home/{OWNER}", context));
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
