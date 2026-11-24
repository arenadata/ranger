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


import org.apache.ranger.plugin.model.RangerServiceDef;

public class RangerTagResource extends RangerAccessResourceImpl {
	private static final String KEY_TAG = "tag";

	private final RangerAccessResource taggedResource;

	public RangerTagResource(String tagType, RangerServiceDef tagServiceDef) {
		super.setValue(KEY_TAG, tagType);
		super.setServiceDef(tagServiceDef);

		this.taggedResource = null;
	}

	public RangerTagResource(String tagType, RangerServiceDef tagServiceDef, String ownerUser) {
		super.setValue(KEY_TAG, tagType);
		super.setServiceDef(tagServiceDef);
		super.setOwnerUser(ownerUser);

		this.taggedResource = null;
	}

	/**
	 * Creates a tag resource owned by the owner of the tagged resource. The owner is read only when a tag policy item
	 * refers to it: some plugins look the resource owner up with a remote call.
	 */
	public RangerTagResource(String tagType, RangerServiceDef tagServiceDef, RangerAccessResource taggedResource) {
		super.setValue(KEY_TAG, tagType);
		super.setServiceDef(tagServiceDef);

		this.taggedResource = taggedResource;
	}

	@Override
	public String getOwnerUser() {
		return taggedResource != null ? taggedResource.getOwnerUser() : super.getOwnerUser();
	}
}
