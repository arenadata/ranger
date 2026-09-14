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

package org.apache.ranger.solr;

import org.apache.ranger.common.SearchCriteria;
import org.apache.ranger.common.SearchField;
import org.apache.ranger.common.SortField;
import org.apache.ranger.common.StringUtil;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** setFieldForPhraseSearch and its dispatch in searchResources: a tokenized value must reach Solr as one quoted phrase, never as a bare term that the parser would split and OR */
public class TestSolrUtil {
    private final SolrUtil solrUtil = new SolrUtil();

    // ---- setFieldForPhraseSearch ----

    @Test
    public void wrapsValueInQuotes() {
        assertEquals("reason:\"chained_service=spark_a\"", solrUtil.setFieldForPhraseSearch("reason", "chained_service=spark_a"));
    }

    @Test
    public void keepsMultipleTokensInOnePhrase() {
        assertEquals("reason:\"chained_service=spark_a chained_policy=not_found\"",
                     solrUtil.setFieldForPhraseSearch("reason", "chained_service=spark_a chained_policy=not_found"));
    }

    @Test
    public void lowercasesValue() {
        assertEquals("reason:\"chained_service=spark_a\"", solrUtil.setFieldForPhraseSearch("reason", "Chained_Service=SPARK_A"));
    }

    @Test
    public void trimsSurroundingWhitespace() {
        assertEquals("reason:\"spark_a\"", solrUtil.setFieldForPhraseSearch("reason", "  spark_a \t"));
    }

    @Test
    public void escapesQuotesInsideValue() {
        assertEquals("reason:\"a \\\"b\\\" c\"", solrUtil.setFieldForPhraseSearch("reason", "a \"b\" c"));
    }

    @Test
    public void escapesBackslashesInsideValue() {
        assertEquals("reason:\"a\\\\b\"", solrUtil.setFieldForPhraseSearch("reason", "a\\b"));
    }

    @Test
    public void escapesBackslashBeforeQuote() {
        // input: a\"b  -> backslash doubled, then quote escaped: a\\\"b
        assertEquals("reason:\"a\\\\\\\"b\"", solrUtil.setFieldForPhraseSearch("reason", "a\\\"b"));
    }

    @Test
    public void returnsNullForNull() {
        assertNull(solrUtil.setFieldForPhraseSearch("reason", null));
    }

    @Test
    public void returnsNullForBlank() {
        assertNull(solrUtil.setFieldForPhraseSearch("reason", "   "));
    }

    // ---- searchResources: which query form each SEARCH_TYPE produces ----

    @Test
    public void searchResourcesQuotesPhraseFields() {
        assertArrayEquals(new String[] {"reason:\"chained_service=spark_a\""},
                          filterQueries(field("reason", SearchField.SEARCH_TYPE.PHRASE), "reason", "chained_service=spark_a"));
    }

    /** the form that ORs the tokens - kept for FULL fields, which are all single-token in the audit schema */
    @Test
    public void searchResourcesKeepsTermQueryForFullFields() {
        assertArrayEquals(new String[] {"reason:chained_service=spark_a"},
                          filterQueries(field("reason", SearchField.SEARCH_TYPE.FULL), "reason", "chained_service=spark_a"));
    }

    @Test
    public void searchResourcesWildcardsPartialFields() {
        assertArrayEquals(new String[] {"reqData:*select*"},
                          filterQueries(field("reqData", SearchField.SEARCH_TYPE.PARTIAL), "requestData", "select"));
    }

    @Test
    public void searchResourcesSkipsAbsentParams() {
        assertNull(filterQueries(field("reason", SearchField.SEARCH_TYPE.PHRASE), "requestData", "select"));
    }

    @Test
    public void searchResourcesSkipsBlankParams() {
        assertNull(filterQueries(field("reason", SearchField.SEARCH_TYPE.PHRASE), "reason", "   "));
    }

    /** runs searchResources against a stubbed Solr and returns the filter queries it built */
    private static String[] filterQueries(SearchField searchField, String paramName, String paramValue) {
        List<SolrQuery> captured = new ArrayList<>();
        SolrUtil util = new SolrUtil() {
            @Override
            public QueryResponse runQuery(SolrClient solrClient, SolrQuery solrQuery) {
                captured.add(solrQuery);
                QueryResponse ok = Mockito.mock(QueryResponse.class);
                Mockito.when(ok.getStatus()).thenReturn(0);
                return ok;
            }
        };
        util.stringUtil = new StringUtil();

        SearchCriteria criteria = new SearchCriteria();
        criteria.addParam(paramName, paramValue);
        util.searchResources(criteria, Collections.singletonList(searchField), Collections.<SortField>emptyList(), null);

        assertEquals(1, captured.size());
        return captured.get(0).getFilterQueries();
    }

    private static SearchField field(String solrField, SearchField.SEARCH_TYPE searchType) {
        // the client-side name is what AccessAuditsService uses for reason and requestData
        String clientName = "reqData".equals(solrField) ? "requestData" : solrField;

        return new SearchField(clientName, solrField, SearchField.DATA_TYPE.STRING, searchType);
    }
}
