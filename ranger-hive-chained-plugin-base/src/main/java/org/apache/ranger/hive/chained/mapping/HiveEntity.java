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

package org.apache.ranger.hive.chained.mapping;

import java.util.Arrays;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;

@Data
@RequiredArgsConstructor
public class HiveEntity {
    private final List<String> nameSegments;
    private final HiveObjectType type;

    public HiveEntity(String fullName, HiveObjectType type) {
        this.nameSegments = toNameSegments(fullName);
        this.type = type;
    }

    public String fullName() {
        return String.join(".", nameSegments);
    }

    private List<String> toNameSegments(String fullName) {
        return Arrays.asList(fullName.split("\\."));
    }
}
