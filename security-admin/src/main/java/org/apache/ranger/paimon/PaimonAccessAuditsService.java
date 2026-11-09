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

package org.apache.ranger.paimon;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.predicate.PredicateBuilder;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowType;
import org.apache.ranger.AccessAuditsService;
import org.apache.ranger.audit.paimon.PaimonAuditSchema;
import org.apache.ranger.common.MessageEnums;
import org.apache.ranger.common.PropertiesUtil;
import org.apache.ranger.common.RESTErrorUtil;
import org.apache.ranger.common.SearchCriteria;
import org.apache.ranger.common.SortField;
import org.apache.ranger.entity.XXService;
import org.apache.ranger.entity.XXServiceDef;
import org.apache.ranger.view.VXAccessAudit;
import org.apache.ranger.view.VXAccessAuditList;
import org.apache.ranger.view.VXLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Reads access audits from the Paimon audit table written by
 * PaimonAuditDestination. Search predicates are pushed down to the scan
 * (partition pruning + file-statistics skipping); sorting and pagination are
 * resolved with a bounded heap over the pruned splits, and the result count is
 * approximate, taken from file row-count metadata.
 */
@Service
@Scope("singleton")
public class PaimonAccessAuditsService extends AccessAuditsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaimonAccessAuditsService.class);

    public static final String PROP_WAREHOUSE = "ranger.paimon.audit.warehouse";
    public static final String PROP_DATABASE = "ranger.paimon.audit.database";
    public static final String PROP_TABLE = "ranger.paimon.audit.table";
    public static final String PROP_CATALOG_TYPE = "ranger.paimon.audit.catalog.type";
    public static final String PROP_CATALOG_URI = "ranger.paimon.audit.catalog.uri";

    private static final int MAX_HEAP_CAPACITY = 10_000;

    private static final String HIVE_SERVICE_TYPE = "hive";

    // restErrorUtil is inherited from AccessAuditsService
    private volatile Table table;
    private volatile String errorMessage;
    private String warehouse;
    private String database = PaimonAuditSchema.DEFAULT_DATABASE;
    private String tableName = PaimonAuditSchema.DEFAULT_TABLE;
    private String catalogType;
    private String catalogUri;
    private boolean configLoaded;

    public void init(Properties props) {
        this.warehouse = props.getProperty(PROP_WAREHOUSE);
        this.database = props.getProperty(PROP_DATABASE, PaimonAuditSchema.DEFAULT_DATABASE);
        this.tableName = props.getProperty(PROP_TABLE, PaimonAuditSchema.DEFAULT_TABLE);
        this.catalogType = props.getProperty(PROP_CATALOG_TYPE);
        this.catalogUri = props.getProperty(PROP_CATALOG_URI);
        this.configLoaded = true;
        this.table = null;
    }

    public VXAccessAuditList searchXAccessAudits(SearchCriteria searchCriteria) {
        Table auditTable = auditTable();
        if (auditTable == null) {
            throw searchError(errorMessage, null);
        }

        updateUserExclusion(searchCriteria.getParamList());

        RowType rowType = auditTable.rowType();
        PredicateBuilder builder = new PredicateBuilder(rowType);
        // PARTIAL (like/contains) is not pushable to the parquet reader
        // (ParquetFilters.visitLike throws UOE and is silently dropped), so those
        // predicates are applied as a residual Java-side filter instead
        List<java.util.function.Predicate<InternalRow>> residualMatchers = new ArrayList<>();
        List<Predicate> predicates = buildPredicates(builder, rowType, searchCriteria.getParamList(), residualMatchers);

        int start = searchCriteria.getStartIndex();
        int maxRows = searchCriteria.getMaxRows() <= 0 ? 25 : searchCriteria.getMaxRows();
        // clamp: the client controls the offset; a huge one would OOM the heap
        int heapCapacity = Math.max(1, Math.min(start + maxRows, MAX_HEAP_CAPACITY));
        if (start + maxRows > MAX_HEAP_CAPACITY) {
            start = Math.max(0, MAX_HEAP_CAPACITY - maxRows);
        }

        List<Split> splits;
        long startTime = System.currentTimeMillis();
        long totalRows;
        // pre-bind typed getters once per query: per-row column access must not
        // resolve field names or re-switch on types for every scanned row
        int seqIdx = rowType.getFieldIndex(PaimonAuditSchema.COLUMN_NAMES[PaimonAuditSchema.IDX_SEQ_NUM]);
        int sortIdx = sortColumnIndex(rowType, searchCriteria);
        InternalRow.FieldGetter[] getters = new InternalRow.FieldGetter[rowType.getFieldCount()];
        for (int i = 0; i < getters.length; i++) {
            getters[i] = InternalRow.createFieldGetter(rowType.getTypeAt(i), i);
        }
        Comparator<HeapEntry> comparator = heapComparator(isAscending(searchCriteria));
        // min-heap on the reversed order so that peek() is the current worst entry
        java.util.PriorityQueue<HeapEntry> heap = new java.util.PriorityQueue<>(heapCapacity, comparator.reversed());
        // scratch entry for the reject-compare: no snapshot allocation unless admitted
        HeapEntry scratch = new HeapEntry();
        try {
            ReadBuilder readBuilder = auditTable.newReadBuilder().withFilter(predicates);
            splits = readBuilder.newScan().plan().splits();
            // the scan visits every row surviving the pushed-down predicates anyway,
            // so the residual pass doubles as the exact totalCount source — an
            // approximate page-level count would leave UI pagination pages empty
            final long[] exactCount = {0};
            if (!splits.isEmpty()) {
                try (RecordReader<InternalRow> reader = readBuilder.newRead().createReader(splits)) {
                    reader.forEachRemaining(row -> {
                        if (!matchesAll(residualMatchers, row)) {
                            return;
                        }
                        exactCount[0]++;
                        scratch.seqNum = seqIdx >= 0 && !row.isNullAt(seqIdx) ? row.getLong(seqIdx) : 0L;
                        scratch.sortKey = sortIdx >= 0 && !row.isNullAt(sortIdx)
                                ? copyComparable(getters[sortIdx].getFieldOrNull(row)) : null;
                        boolean admit = heap.size() < heapCapacity
                                || comparator.compare(scratch, heap.peek()) < 0;
                        if (admit) {
                            heap.add(new HeapEntry(scratch.seqNum, scratch.sortKey, snapshot(row, getters)));
                        }
                    });
                }
            }
            totalRows = exactCount[0];
        } catch (Throwable t) {
            LOGGER.error("Failed to read audit records from Paimon", t);
            if (restErrorUtil == null) {
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                }
                throw new RuntimeException(t);
            }
            throw searchError("Error connecting to search engine", t);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("searchXAccessAudits: splits=" + splits.size() + ", scanMs="
                    + (System.currentTimeMillis() - startTime));
        }

        List<HeapEntry> sorted = new ArrayList<>(heap);
        sorted.sort(comparator);
        List<VXAccessAudit> page = new ArrayList<>();
        for (int i = start; i < sorted.size() && i < start + maxRows; i++) {
            page.add(populateViewBean(sorted.get(i).snapshot));
        }
        // dao enrichment is done only for the returned page, not per scanned row
        enrichWithDao(page);

        VXAccessAuditList returnList = new VXAccessAuditList();
        returnList.setPageSize(maxRows);
        returnList.setResultSize(page.size());
        returnList.setStartIndex(searchCriteria.getStartIndex());
        returnList.setTotalCount((int) Math.min(Integer.MAX_VALUE, totalRows));
        returnList.setVXAccessAudits(page);
        return returnList;
    }

    private void enrichWithDao(List<VXAccessAudit> page) {
        if (daoManager == null) {
            return;
        }
        for (VXAccessAudit audit : page) {
            if (audit.getRepoName() != null) {
                XXService xxService = daoManager.getXXService().findByName(audit.getRepoName());
                if (xxService != null) {
                    audit.setRepoDisplayName(xxService.getDisplayName());
                }
            }
            if (audit.getRepoType() != 0) {
                XXServiceDef serviceDef = daoManager.getXXServiceDef().getById((long) audit.getRepoType());
                if (serviceDef != null) {
                    audit.setServiceType(serviceDef.getName());
                    audit.setServiceTypeDisplayName(serviceDef.getDisplayName());
                }
            }
        }
    }

    public VXLong getXAccessAuditSearchCount(SearchCriteria searchCriteria) {
        Table auditTable = auditTable();
        VXLong ret = new VXLong();
        if (auditTable == null) {
            ret.setValue(0L);
            return ret;
        }
        List<Predicate> predicates = buildPredicates(
                new PredicateBuilder(auditTable.rowType()), auditTable.rowType(), searchCriteria.getParamList(),
                new ArrayList<>());
        ret.setValue(approximateCount(auditTable, predicates));
        return ret;
    }

    private long approximateCount(Table auditTable, List<Predicate> predicates) {
        List<Split> splits = auditTable.newReadBuilder().withFilter(predicates).newScan().plan().splits();
        long rows = 0;
        for (Split split : splits) {
            rows += ((DataSplit) split).rowCount();
        }
        return rows;
    }

    private RuntimeException searchError(String message, Throwable cause) {
        RuntimeException ret;
        if (restErrorUtil == null) {
            ret = new RuntimeException(message);
        } else {
            ret = restErrorUtil.createRESTException(message, MessageEnums.ERROR_SYSTEM);
        }
        if (cause != null) {
            ret.initCause(cause);
        }
        return ret;
    }

    private Table auditTable() {
        Table auditTable = this.table;
        if (auditTable != null) {
            return auditTable;
        }
        synchronized (this) {
            if (this.table != null) {
                return this.table;
            }
            try {
                loadConfig();
                Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(
                        PaimonAuditSchema.catalogOptions(warehouse, catalogType, catalogUri)));
                try {
                    this.table = catalog.getTable(Identifier.create(database, tableName));
                    this.errorMessage = null;
                } finally {
                    catalog.close();
                }
            } catch (Throwable t) {
                this.errorMessage = "Failed to open Paimon audit table " + database + "." + tableName
                        + " at " + warehouse + ": " + t.getMessage();
                LOGGER.error(errorMessage, t);
            }
            return this.table;
        }
    }

    private void loadConfig() {
        if (configLoaded) {
            return;
        }
        this.warehouse = PropertiesUtil.getProperty(PROP_WAREHOUSE);
        this.database = PropertiesUtil.getProperty(PROP_DATABASE, PaimonAuditSchema.DEFAULT_DATABASE);
        this.tableName = PropertiesUtil.getProperty(PROP_TABLE, PaimonAuditSchema.DEFAULT_TABLE);
        this.catalogType = PropertiesUtil.getProperty(PROP_CATALOG_TYPE);
        this.catalogUri = PropertiesUtil.getProperty(PROP_CATALOG_URI);
        this.configLoaded = true;
    }

    private List<Predicate> buildPredicates(PredicateBuilder builder, RowType rowType,
                                            Map<String, Object> paramList,
                                            List<java.util.function.Predicate<InternalRow>> residualMatchers) {
        List<Predicate> predicates = new ArrayList<>();
        if (paramList == null || paramList.isEmpty()) {
            return predicates;
        }

        Map<String, String> nameToColumn = new HashMap<>();
        for (org.apache.ranger.common.SearchField field : searchFields) {
            if (!field.getClientFieldName().startsWith("-")) {
                nameToColumn.put(field.getClientFieldName(), field.getFieldName());
            }
        }

        // bind the event-time index once: every date residual matcher uses it
        int evtTimeIdx = rowType.getFieldIndex("evtTime");

        for (Map.Entry<String, Object> entry : paramList.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (value == null || (value instanceof String && ((String) value).isEmpty())) {
                continue;
            }
            if ("startDate".equals(name)) {
                Timestamp bound = toTimestamp(value);
                if (bound != null) {
                    predicates.add(builder.greaterOrEqual(evtTimeIdx, bound));
                    long boundMillis = bound.getMillisecond();
                    residualMatchers.add(row -> evtTimeMillis(row, evtTimeIdx) >= boundMillis);
                }
                continue;
            }
            if ("endDate".equals(name)) {
                Timestamp bound = toTimestamp(value);
                if (bound != null) {
                    predicates.add(builder.lessOrEqual(evtTimeIdx, bound));
                    long boundMillis = bound.getMillisecond();
                    residualMatchers.add(row -> evtTimeMillis(row, evtTimeIdx) <= boundMillis);
                }
                continue;
            }
            if ("excludeUser".equals(name)) {
                addStringPredicate(builder, rowType, "reqUser", value, predicates, true);
                continue;
            }
            if ("eventId".equals(name)) {
                // same remap as the Solr source
                addStringPredicate(builder, rowType, "id", value, predicates, false);
                continue;
            }

            boolean exclude = name.startsWith("-");
            String baseName = exclude ? name.substring(1) : name;
            String column = nameToColumn.get(baseName);
            if (column == null || !rowType.getFieldNames().contains(column)) {
                continue;
            }
            org.apache.ranger.common.SearchField field = findField(baseName);
            org.apache.ranger.common.SearchField.DATA_TYPE dataType = field != null
                    ? field.getDataType() : org.apache.ranger.common.SearchField.DATA_TYPE.STRING;
            boolean stringType = dataType == org.apache.ranger.common.SearchField.DATA_TYPE.STRING
                    || dataType == org.apache.ranger.common.SearchField.DATA_TYPE.STR_LIST;

            if (stringType) {
                boolean partial = !exclude && field != null
                        && field.getSearchType() == org.apache.ranger.common.SearchField.SEARCH_TYPE.PARTIAL;
                if (partial) {
                    // like/contains is not pushable at all — residual filter only
                    addContainsMatcher(rowType, column, value, residualMatchers);
                } else {
                    // parquet predicate filtering works at page/row-group granularity;
                    // rows surviving the page still need an exact check
                    addStringPredicate(builder, rowType, column, value, predicates, exclude);
                    addStringMatcher(rowType, column, value, residualMatchers, exclude);
                }
            } else {
                addScalarPredicate(builder, rowType, column, value, predicates, exclude);
                addScalarMatcher(rowType, column, value, residualMatchers, exclude);
            }
        }
        return predicates;
    }

    private void addStringMatcher(RowType rowType, String column, Object value,
                                  List<java.util.function.Predicate<InternalRow>> matchers,
                                  boolean exclude) {
        List<Object> literals = toLiterals(value);
        if (literals.isEmpty()) {
            return;
        }
        // BinaryString literals + byte-based equals/hashCode: the row value is a
        // zero-copy view, compared directly — no per-row decode/allocation
        int idx = rowType.getFieldIndex(column);
        Set<BinaryString> literalsBs = new HashSet<>();
        for (Object literal : literals) {
            literalsBs.add(BinaryString.fromString(String.valueOf(literal)));
        }
        matchers.add(row -> {
            BinaryString v = row.isNullAt(idx) ? null : row.getString(idx);
            return exclude ? v == null || !literalsBs.contains(v)
                    : v != null && literalsBs.contains(v);
        });
    }

    private void addScalarMatcher(RowType rowType, String column, Object value,
                                  List<java.util.function.Predicate<InternalRow>> matchers,
                                  boolean exclude) {
        List<Object> literals = toLiterals(value);
        if (literals.isEmpty()) {
            return;
        }
        int idx = rowType.getFieldIndex(column);
        List<Object> typedLiterals = new ArrayList<>();
        for (Object literal : literals) {
            Object typed = toScalar(rowType.getFieldTypes().get(idx), literal);
            if (typed != null) {
                typedLiterals.add(typed);
            }
        }
        matchers.add(row -> {
            boolean anyMatch = false;
            for (Object typed : typedLiterals) {
                if (typed instanceof Long) {
                    anyMatch = !row.isNullAt(idx) && row.getLong(idx) == (Long) typed;
                } else if (typed instanceof Integer) {
                    anyMatch = !row.isNullAt(idx) && row.getInt(idx) == (Integer) typed;
                } else {
                    BinaryString v = row.isNullAt(idx) ? null : row.getString(idx);
                    anyMatch = v != null && v.equals(typed);
                }
                if (anyMatch) {
                    break;
                }
            }
            return exclude ? !anyMatch : anyMatch;
        });
    }

    private void addContainsMatcher(RowType rowType, String column, Object value,
                                    List<java.util.function.Predicate<InternalRow>> matchers) {
        List<Object> literals = toLiterals(value);
        if (literals.isEmpty()) {
            return;
        }
        int idx = rowType.getFieldIndex(column);
        BinaryString[] patterns = new BinaryString[literals.size()];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = BinaryString.fromString(String.valueOf(literals.get(i)));
        }
        matchers.add(row -> {
            BinaryString v = row.isNullAt(idx) ? null : row.getString(idx);
            if (v == null) {
                return false;
            }
            for (BinaryString pattern : patterns) {
                if (v.contains(pattern)) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean matchesAll(List<java.util.function.Predicate<InternalRow>> matchers, InternalRow row) {
        for (java.util.function.Predicate<InternalRow> matcher : matchers) {
            if (!matcher.test(row)) {
                return false;
            }
        }
        return true;
    }

    private long evtTimeMillis(InternalRow row, int evtTimeIdx) {
        return row.isNullAt(evtTimeIdx) ? Long.MIN_VALUE : row.getTimestamp(evtTimeIdx, 3).getMillisecond();
    }

    private org.apache.ranger.common.SearchField findField(String name) {
        for (org.apache.ranger.common.SearchField field : searchFields) {
            if (field.getClientFieldName().equals(name)) {
                return field;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void addStringPredicate(PredicateBuilder builder, RowType rowType, String column,
                                    Object value, List<Predicate> predicates, boolean exclude) {
        int idx = rowType.getFieldIndex(column);
        List<Object> literals = toLiterals(value);
        if (literals.isEmpty()) {
            return;
        }
        List<Object> binaryLiterals = new ArrayList<>();
        for (Object literal : literals) {
            binaryLiterals.add(BinaryString.fromString(String.valueOf(literal)));
        }
        if (binaryLiterals.size() == 1) {
            predicates.add(exclude ? builder.notEqual(idx, binaryLiterals.get(0))
                    : builder.equal(idx, binaryLiterals.get(0)));
        } else {
            predicates.add(exclude ? builder.notIn(idx, binaryLiterals)
                    : builder.in(idx, binaryLiterals));
        }
    }

    private void addScalarPredicate(PredicateBuilder builder, RowType rowType, String column,
                                    Object value, List<Predicate> predicates, boolean exclude) {
        int idx = rowType.getFieldIndex(column);
        List<Object> literals = toLiterals(value);
        List<Object> typedLiterals = new ArrayList<>();
        for (Object literal : literals) {
            Object typed = toScalar(rowType.getFieldTypes().get(idx), literal);
            if (typed != null) {
                typedLiterals.add(typed);
            }
        }
        if (typedLiterals.isEmpty()) {
            return;
        }
        if (typedLiterals.size() == 1) {
            predicates.add(exclude ? builder.notEqual(idx, typedLiterals.get(0))
                    : builder.equal(idx, typedLiterals.get(0)));
        } else {
            predicates.add(exclude ? builder.notIn(idx, typedLiterals)
                    : builder.in(idx, typedLiterals));
        }
    }

    private Object toScalar(org.apache.paimon.types.DataType type, Object value) {
        if (value == null) {
            return null;
        }
        switch (type.getTypeRoot()) {
            case BIGINT:
                return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
            case INTEGER:
                return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(value.toString());
            default:
                return BinaryString.fromString(value.toString());
        }
    }

    private List<Object> toLiterals(Object value) {
        if (value instanceof Collection) {
            return new ArrayList<>(((Collection<?>) value));
        }
        if (value instanceof Object[]) {
            return Arrays.asList((Object[]) value);
        }
        if (value instanceof String) {
            String str = ((String) value).trim();
            return str.isEmpty() ? Collections.emptyList() : Collections.singletonList(str);
        }
        return Collections.singletonList(value);
    }

    private Timestamp toTimestamp(Object value) {
        if (value instanceof Date) {
            return Timestamp.fromEpochMillis(((Date) value).getTime());
        }
        if (value instanceof Number) {
            return Timestamp.fromEpochMillis(((Number) value).longValue());
        }
        try {
            return Timestamp.fromEpochMillis(Long.parseLong(value.toString()));
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private int sortColumnIndex(RowType rowType, SearchCriteria searchCriteria) {
        int sortIdx = rowType.getFieldIndex("evtTime");
        if (searchCriteria.getSortBy() != null && !searchCriteria.getSortBy().isEmpty()) {
            for (SortField field : sortFields) {
                if (field.getParamName().equals(searchCriteria.getSortBy())) {
                    int mapped = rowType.getFieldIndex(field.getFieldName());
                    if (mapped >= 0) {
                        sortIdx = mapped;
                    }
                    break;
                }
            }
        }
        return sortIdx;
    }

    private boolean isAscending(SearchCriteria searchCriteria) {
        if (searchCriteria.getSortType() != null && !searchCriteria.getSortType().isEmpty()) {
            return "asc".equalsIgnoreCase(searchCriteria.getSortType());
        }
        return false;
    }

    private Comparator<HeapEntry> heapComparator(boolean ascending) {
        Comparator<HeapEntry> cmp = (a, b) -> {
            int result = compareRaw(a.sortKey, b.sortKey);
            return ascending ? result : -result;
        };
        Comparator<HeapEntry> tieBreak = (a, b) -> Long.compare(b.seqNum, a.seqNum);
        return cmp.thenComparing(tieBreak);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareRaw(Comparable a, Comparable b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return a.compareTo(b);
    }

    @SuppressWarnings("unchecked")
    private Comparable<?> copyComparable(Object value) {
        // reader strings are zero-copy views into a reused buffer: they must be
        // materialized (toString) before the reader advances; other types are
        // immutable values (Timestamp, boxed numbers) and safe to keep
        if (value instanceof BinaryString) {
            return value.toString();
        }
        return (Comparable<?>) value;
    }

    private Object[] snapshot(InternalRow row, InternalRow.FieldGetter[] getters) {
        Object[] snap = new Object[getters.length];
        for (int i = 0; i < snap.length; i++) {
            Object v = getters[i].getFieldOrNull(row);
            snap[i] = v instanceof BinaryString ? v.toString() : v;
        }
        return snap;
    }

    private VXAccessAudit populateViewBean(Object[] f) {
        VXAccessAudit accessAudit = new VXAccessAudit();

        String eventId = (String) f[PaimonAuditSchema.IDX_ID];
        if (eventId != null) {
            // same id conversion as the Solr source so both views agree
            accessAudit.setId((long) eventId.hashCode());
            accessAudit.setEventId(eventId);
        }
        accessAudit.setClusterName((String) f[PaimonAuditSchema.IDX_CLUSTER]);
        accessAudit.setZoneName((String) f[PaimonAuditSchema.IDX_ZONE_NAME]);
        accessAudit.setAgentHost((String) f[PaimonAuditSchema.IDX_AGENT_HOST]);
        accessAudit.setPolicyVersion((Long) f[PaimonAuditSchema.IDX_POLICY_VERSION]);
        String accessType = (String) f[PaimonAuditSchema.IDX_ACCESS];
        accessAudit.setAccessType(accessType);
        accessAudit.setAclEnforcer((String) f[PaimonAuditSchema.IDX_ENFORCER]);
        accessAudit.setAgentId((String) f[PaimonAuditSchema.IDX_AGENT]);

        String repoName = (String) f[PaimonAuditSchema.IDX_REPO];
        accessAudit.setRepoName(repoName);
        // repo/service display names are enriched by enrichWithDao() for the page only

        accessAudit.setSessionId((String) f[PaimonAuditSchema.IDX_SESS]);
        accessAudit.setRequestUser((String) f[PaimonAuditSchema.IDX_REQ_USER]);

        String requestData = (String) f[PaimonAuditSchema.IDX_REQ_DATA];
        int repoType = f[PaimonAuditSchema.IDX_REPO_TYPE] == null
                ? 0 : (Integer) f[PaimonAuditSchema.IDX_REPO_TYPE];
        if (requestData != null && isHiveGrantRevoke(accessType, repoType)) {
            try {
                requestData = java.net.URLDecoder.decode(requestData, "UTF-8");
            } catch (UnsupportedEncodingException ignore) {
                // keep original
            }
        }
        accessAudit.setRequestData(requestData);

        accessAudit.setResourcePath((String) f[PaimonAuditSchema.IDX_RESOURCE]);
        accessAudit.setClientIP((String) f[PaimonAuditSchema.IDX_CLI_IP]);
        accessAudit.setAccessResult(f[PaimonAuditSchema.IDX_RESULT] == null
                ? 0 : (Integer) f[PaimonAuditSchema.IDX_RESULT]);
        accessAudit.setPolicyId(f[PaimonAuditSchema.IDX_POLICY] == null
                ? 0L : (Long) f[PaimonAuditSchema.IDX_POLICY]);
        accessAudit.setRepoType(repoType);
        accessAudit.setResourceType((String) f[PaimonAuditSchema.IDX_RES_TYPE]);
        accessAudit.setResultReason((String) f[PaimonAuditSchema.IDX_REASON]);
        accessAudit.setAction((String) f[PaimonAuditSchema.IDX_ACTION]);

        Timestamp evtTime = (Timestamp) f[PaimonAuditSchema.IDX_EVT_TIME];
        if (evtTime != null) {
            accessAudit.setEventTime(new Date(evtTime.getMillisecond()));
        }
        accessAudit.setSequenceNumber(asLong(f[PaimonAuditSchema.IDX_SEQ_NUM]));
        accessAudit.setEventCount(asLong(f[PaimonAuditSchema.IDX_EVENT_COUNT]));
        accessAudit.setEventDuration(asLong(f[PaimonAuditSchema.IDX_EVENT_DUR_MS]));
        accessAudit.setTags((String) f[PaimonAuditSchema.IDX_TAGS]);
        return accessAudit;
    }

    private static Long asLong(Object value) {
        return value == null ? null : (Long) value;
    }

    private boolean isHiveGrantRevoke(String accessType, int repoType) {
        if (!"grant".equalsIgnoreCase(accessType) && !"revoke".equalsIgnoreCase(accessType)) {
            return false;
        }
        return PropertiesUtil.getBooleanProperty("ranger.audit.hive.query.visibility", true)
                || !isHiveServiceType(repoType);
    }

    private boolean isHiveServiceType(int repoType) {
        if (daoManager == null || repoType == 0) {
            return false;
        }
        XXServiceDef serviceDef = daoManager.getXXServiceDef().getById((long) repoType);
        return serviceDef != null && HIVE_SERVICE_TYPE.equalsIgnoreCase(serviceDef.getName());
    }

    private static final class HeapEntry {
        private long seqNum;
        private Comparable<?> sortKey;
        private Object[] snapshot;

        private HeapEntry() {
        }

        private HeapEntry(long seqNum, Comparable<?> sortKey, Object[] snapshot) {
            this.seqNum = seqNum;
            this.sortKey = sortKey;
            this.snapshot = snapshot;
        }
    }
}
