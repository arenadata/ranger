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

package org.apache.ranger.resource.mapper.hive.model;

import java.util.Optional;

public enum HiveEventType {
    CREATE_TABLE,
    DROP_TABLE,
    ALTER_TABLE,
    CREATE_DATABASE,
    DROP_DATABASE,
    ALTER_DATABASE;

    public static Optional<HiveEventType> from(String eventType) {
        try {
            return Optional.of(HiveEventType.valueOf(eventType));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isSupported(String rawEventType) {
        return from(rawEventType).isPresent();
    }
}
