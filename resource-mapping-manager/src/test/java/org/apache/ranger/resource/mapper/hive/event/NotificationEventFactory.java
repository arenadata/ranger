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

package org.apache.ranger.resource.mapper.hive.event;

import static org.apache.ranger.resource.mapper.hive.model.HiveEventType.ALTER_DATABASE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEventType.ALTER_TABLE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEventType.CREATE_DATABASE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEventType.CREATE_TABLE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEventType.DROP_DATABASE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEventType.DROP_TABLE;

import lombok.Getter;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.NotificationEvent;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.messaging.AlterDatabaseMessage;
import org.apache.hadoop.hive.metastore.messaging.AlterTableMessage;
import org.apache.hadoop.hive.metastore.messaging.CreateDatabaseMessage;
import org.apache.hadoop.hive.metastore.messaging.CreateTableMessage;
import org.apache.hadoop.hive.metastore.messaging.DropDatabaseMessage;
import org.apache.hadoop.hive.metastore.messaging.DropTableMessage;
import org.apache.hadoop.hive.metastore.messaging.MessageSerializer;
import org.apache.hadoop.hive.metastore.messaging.json.JSONAlterDatabaseMessage;
import org.apache.hadoop.hive.metastore.messaging.json.JSONAlterTableMessage;
import org.apache.hadoop.hive.metastore.messaging.json.JSONCreateDatabaseMessage;
import org.apache.hadoop.hive.metastore.messaging.json.JSONCreateTableMessage;
import org.apache.hadoop.hive.metastore.messaging.json.JSONDropDatabaseMessage;
import org.apache.hadoop.hive.metastore.messaging.json.JSONDropTableMessage;
import org.apache.hadoop.hive.metastore.messaging.json.JSONMessageEncoder;
import org.apache.ranger.resource.mapper.model.ResourceMapping;

public class NotificationEventFactory {
    private static final MessageSerializer MESSAGE_ENCODER = new JSONMessageEncoder().getSerializer();

    public static NotificationEvent newCreateTableEvent(long id, String fullName, TableType tableType, String location) {
        EntityName entityName = new EntityName(fullName);
        NotificationEvent event = baseEvent(id, entityName);
        event.setEventType(CREATE_TABLE.name());

        CreateTableMessage message = new JSONCreateTableMessage(
            "server:1234",
            "serverPrincipal",
            buildTable(entityName, tableType, location),
            null,
            0L
        );

        event.setMessage(MESSAGE_ENCODER.serialize(message));
        return event;
    }

    public static NotificationEvent newCreateDbEvent(long id, String fullName, String location) {
        EntityName entityName = new EntityName(fullName);
        NotificationEvent event = baseEvent(id, entityName);
        event.setEventType(CREATE_DATABASE.name());

        CreateDatabaseMessage message = new JSONCreateDatabaseMessage(
            "server:1234",
            "serverPrincipal",
            buildDb(entityName, location),
            0L
        );
        event.setMessage(MESSAGE_ENCODER.serialize(message));
        return event;
    }

    public static NotificationEvent newAlterTableEvent(long id,
                                                       TableType tableType,
                                                       ResourceMapping oldMapping,
                                                       ResourceMapping newMapping) {
        EntityName entityName = new EntityName(oldMapping.getName());
        NotificationEvent event = baseEvent(id, entityName);
        event.setEventType(ALTER_TABLE.name());

        AlterTableMessage message = new JSONAlterTableMessage(
            "server:1234",
            "serverPrincipal",
            buildTable(entityName, tableType, oldMapping.getLocation()),
            buildTable(new EntityName(newMapping.getName()), tableType, newMapping.getLocation()),
            false,
            null,
            0L
        );
        event.setMessage(MESSAGE_ENCODER.serialize(message));
        return event;
    }

    public static NotificationEvent newAlterDbEvent(long id, ResourceMapping oldMapping, ResourceMapping newMapping) {
        EntityName entityName = new EntityName(oldMapping.getName());
        NotificationEvent event = baseEvent(id, entityName);
        event.setEventType(ALTER_DATABASE.name());

        AlterDatabaseMessage message = new JSONAlterDatabaseMessage(
            "server:1234",
            "serverPrincipal",
            buildDb(entityName, oldMapping.getLocation()),
            buildDb(new EntityName(newMapping.getName()), newMapping.getLocation()),
            0L
        );
        event.setMessage(MESSAGE_ENCODER.serialize(message));
        return event;
    }

    public static NotificationEvent newDropTableEvent(long id, String fullName, TableType tableType, String location) {
        EntityName entityName = new EntityName(fullName);
        NotificationEvent event = baseEvent(id, entityName);
        event.setEventType(DROP_TABLE.name());

        DropTableMessage message = new JSONDropTableMessage(
            "server:1234",
            "serverPrincipal",
            buildTable(entityName, tableType, location),
            0L
        );
        event.setMessage(MESSAGE_ENCODER.serialize(message));
        return event;
    }

    public static NotificationEvent newDropDbEvent(long id, String fullName, String location) {
        EntityName entityName = new EntityName(fullName);
        NotificationEvent event = baseEvent(id, entityName);
        event.setEventType(DROP_DATABASE.name());

        DropDatabaseMessage message = new JSONDropDatabaseMessage(
            "server:1234",
            "serverPrincipal",
            buildDb(entityName, location),
            0L
        );
        event.setMessage(MESSAGE_ENCODER.serialize(message));
        return event;
    }

    private static NotificationEvent baseEvent(long id, EntityName entityName) {
        NotificationEvent event = new NotificationEvent();
        event.setEventId(id);
        event.setEventTime(0);

        event.setCatName(entityName.catalogName);
        event.setDbName(entityName.dbName);
        event.setTableName(entityName.tableName);

        return event;
    }

    private static Table buildTable(EntityName entityName, TableType tableType, String location) {
        Table table = new Table();
        table.setCatName(entityName.catalogName);
        table.setDbName(entityName.dbName);
        table.setTableName(entityName.tableName);
        table.setTableType(tableType.toString());

        StorageDescriptor sd = new StorageDescriptor();
        sd.setLocation(location);

        table.setSd(sd);
        return table;
    }

    private static Database buildDb(EntityName entityName, String location) {
        Database db = new Database();
        db.setCatalogName(entityName.catalogName);
        db.setName(entityName.dbName);
        db.setLocationUri(location);
        return db;
    }

    @Getter
    private static class EntityName {
        private final String catalogName;
        private final String dbName;
        private final String tableName;

        private EntityName(String fullName) {
            String[] nameParts = fullName.split("\\.");
            if (nameParts.length < 2) {
                throw new IllegalArgumentException("Invalid fullName : " + fullName);
            }

            this.catalogName = nameParts[0];
            this.dbName = nameParts[1];
            this.tableName = nameParts.length == 3
                ? nameParts[2]
                : null;
        }
    }
}

