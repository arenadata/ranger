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

package org.apache.ranger.audit.paimon;

import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.options.Options;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.ranger.audit.model.AuthzAuditEvent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * Single source of the audit table schema: column names/types/order, the
 * event-to-row mapping and the shared catalog options. Consumed by the
 * destination (write), the admin read service and the maintenance jobs.
 */
public final class PaimonAuditSchema {
    public static final String DEFAULT_DATABASE = "audit";
    public static final String DEFAULT_TABLE = "access_audit";
    public static final String DEFAULT_PARTITION_EXPIRATION = "90 d";

    public static final String[] COLUMN_NAMES = {
        "dt", "evtTime", "id", "access", "enforcer", "agent", "repo", "sess",
        "reqUser", "reqData", "resource", "cliIP", "resType", "reason", "action",
        "tags", "cluster", "zoneName", "agentHost", "logType", "result", "policy",
        "repoType", "seq_num", "event_count", "event_dur_ms", "policyVersion"
    };

    public static final DataType[] COLUMN_TYPES = {
        DataTypes.STRING(), DataTypes.TIMESTAMP(3), DataTypes.STRING(), DataTypes.STRING(),
        DataTypes.STRING(), DataTypes.STRING(), DataTypes.STRING(), DataTypes.STRING(),
        DataTypes.STRING(), DataTypes.STRING(), DataTypes.STRING(), DataTypes.STRING(),
        DataTypes.STRING(), DataTypes.STRING(), DataTypes.STRING(),
        DataTypes.STRING(), DataTypes.STRING(), DataTypes.STRING(), DataTypes.STRING(),
        DataTypes.STRING(), DataTypes.INT(), DataTypes.BIGINT(), DataTypes.INT(),
        DataTypes.BIGINT(), DataTypes.BIGINT(), DataTypes.BIGINT(), DataTypes.BIGINT()
    };

    public static final int IDX_DT = 0;
    public static final int IDX_EVT_TIME = 1;
    public static final int IDX_ID = 2;
    public static final int IDX_ACCESS = 3;
    public static final int IDX_ENFORCER = 4;
    public static final int IDX_AGENT = 5;
    public static final int IDX_REPO = 6;
    public static final int IDX_SESS = 7;
    public static final int IDX_REQ_USER = 8;
    public static final int IDX_REQ_DATA = 9;
    public static final int IDX_RESOURCE = 10;
    public static final int IDX_CLI_IP = 11;
    public static final int IDX_RES_TYPE = 12;
    public static final int IDX_REASON = 13;
    public static final int IDX_ACTION = 14;
    public static final int IDX_TAGS = 15;
    public static final int IDX_CLUSTER = 16;
    public static final int IDX_ZONE_NAME = 17;
    public static final int IDX_AGENT_HOST = 18;
    public static final int IDX_LOG_TYPE = 19;
    public static final int IDX_RESULT = 20;
    public static final int IDX_POLICY = 21;
    public static final int IDX_REPO_TYPE = 22;
    public static final int IDX_SEQ_NUM = 23;
    public static final int IDX_EVENT_COUNT = 24;
    public static final int IDX_EVENT_DUR_MS = 25;
    public static final int IDX_POLICY_VERSION = 26;

    private static final DateTimeFormatter DAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private PaimonAuditSchema() {
    }

    public static Options catalogOptions(String warehouse, String catalogType, String catalogUri) {
        Options options = new Options();
        options.set("warehouse", warehouse);
        if (catalogType != null && !catalogType.isEmpty()
                && !"filesystem".equalsIgnoreCase(catalogType)) {
            options.set("type", catalogType.toLowerCase(Locale.ROOT));
            if (catalogUri != null && !catalogUri.isEmpty()) {
                options.set("uri", catalogUri);
            }
        }
        return options;
    }

    public static String dayString(long epochMillis) {
        return DAY_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    public static InternalRow toRow(AuthzAuditEvent event) {
        long eventMillis = event.getEventTime() == null
                ? System.currentTimeMillis() : event.getEventTime().getTime();

        GenericRow row = new GenericRow(COLUMN_NAMES.length);
        row.setField(IDX_DT, BinaryString.fromString(dayString(eventMillis)));
        row.setField(IDX_EVT_TIME, Timestamp.fromEpochMillis(eventMillis));
        setString(row, IDX_ID, event.getEventId());
        setString(row, IDX_ACCESS, event.getAccessType());
        setString(row, IDX_ENFORCER, event.getAclEnforcer());
        setString(row, IDX_AGENT, event.getAgentId());
        setString(row, IDX_REPO, event.getRepositoryName());
        setString(row, IDX_SESS, event.getSessionId());
        setString(row, IDX_REQ_USER, event.getUser());
        setString(row, IDX_REQ_DATA, event.getRequestData());
        setString(row, IDX_RESOURCE, event.getResourcePath());
        setString(row, IDX_CLI_IP, event.getClientIP());
        setString(row, IDX_RES_TYPE, event.getResourceType());
        setString(row, IDX_REASON, event.getResultReason());
        setString(row, IDX_ACTION, event.getAction());
        setString(row, IDX_TAGS, joinTags(event.getTags()));
        setString(row, IDX_CLUSTER, event.getClusterName());
        setString(row, IDX_ZONE_NAME, event.getZoneName());
        setString(row, IDX_AGENT_HOST, event.getAgentHostname());
        setString(row, IDX_LOG_TYPE, event.getLogType());
        row.setField(IDX_RESULT, (int) event.getAccessResult());
        row.setField(IDX_POLICY, event.getPolicyId());
        row.setField(IDX_REPO_TYPE, event.getRepositoryType());
        row.setField(IDX_SEQ_NUM, event.getSeqNum());
        row.setField(IDX_EVENT_COUNT, event.getEventCount());
        row.setField(IDX_EVENT_DUR_MS, event.getEventDurationMS());
        row.setField(IDX_POLICY_VERSION, event.getPolicyVersion());
        return row;
    }

    private static void setString(GenericRow row, int index, String value) {
        row.setField(index, value == null ? null : BinaryString.fromString(value));
    }

    private static String joinTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return String.join(",", tags);
    }
}
