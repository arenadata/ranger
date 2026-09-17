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

package org.apache.ranger.hive.chained.starrocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The FE resolves the mask type of the chained result against the StarRocks service definition and
 * parses whatever transformer it finds, so every Hive mask type has to come out of the chained
 * plugin as something that definition can apply.
 */
class StarRocksDataMasksTest {
    /** mask types of conf/ranger/ranger-servicedef-starrocks.json */
    private static final RangerServiceDef STARROCKS = serviceDef(
        "MASK", "MASK_HASH", "MASK_NULL", "MASK_NONE", "MASK_DATE_SHOW_YEAR", "CUSTOM");

    // ---- Hive mask types StarRocks cannot apply ----

    @ParameterizedTest(name = "{0} is rewritten as a custom mask")
    @ValueSource(strings = {"MASK_SHOW_LAST_4", "MASK_SHOW_FIRST_4", "MASK_HASH"})
    void unusableHiveMasksBecomeCustomExpressions(String maskType) {
        RangerAccessResult result = translate(maskType);

        assertEquals(RangerPolicy.MASK_TYPE_CUSTOM, result.getMaskType());
        assertTrue(result.getMaskedValue().contains("{col}"),
            "the FE substitutes {col} into a custom masked value: " + result.getMaskedValue());
        assertFalse(result.getMaskedValue().contains("{COL}"),
            "the FE only substitutes the lower-case placeholder: " + result.getMaskedValue());
        assertTrue(result.isMaskEnabled());
    }

    @Test
    void showLastFourKeepsTheLastFourCharacters() {
        String expression = translate("MASK_SHOW_LAST_4").getMaskedValue();

        assertTrue(expression.contains("right("), expression);
        assertTrue(expression.contains("repeat('x'"), expression);
    }

    @Test
    void showFirstFourKeepsTheFirstFourCharacters() {
        String expression = translate("MASK_SHOW_FIRST_4").getMaskedValue();

        assertTrue(expression.contains("left("), expression);
        assertTrue(expression.contains("repeat('x'"), expression);
    }

    /** the StarRocks MASK_HASH transformer spells {COL}, which the FE never substitutes */
    @Test
    void hashIsRewrittenEvenThoughStarRocksDefinesIt() {
        assertTrue(translate("MASK_HASH").getMaskedValue().contains("sha2("));
    }

    @ParameterizedTest(name = "mask type spelled {0}")
    @ValueSource(strings = {"mask_show_last_4", "Mask_Show_Last_4", "MASK_SHOW_LAST_4"})
    void maskTypeIsMatchedCaseInsensitively(String maskType) {
        assertEquals(RangerPolicy.MASK_TYPE_CUSTOM, translate(maskType).getMaskType());
    }

    // ---- Hive mask types StarRocks applies itself ----

    @ParameterizedTest(name = "{0} is passed through")
    @ValueSource(strings = {"MASK", "MASK_NULL", "MASK_DATE_SHOW_YEAR"})
    void masksStarRocksKnowsArePassedThrough(String maskType) {
        RangerAccessResult result = translate(maskType);

        assertEquals(maskType, result.getMaskType());
        assertNull(result.getMaskedValue());
    }

    /** a Hive custom mask carries Hive SQL; it is left alone, there is nothing to translate it into */
    @Test
    void aHiveCustomMaskKeepsItsExpression() {
        RangerAccessResult result = maskResult(RangerPolicy.MASK_TYPE_CUSTOM);
        result.setMaskedValue("concat('x', {col})");

        StarRocksDataMasks.translate(result, STARROCKS);

        assertEquals(RangerPolicy.MASK_TYPE_CUSTOM, result.getMaskType());
        assertEquals("concat('x', {col})", result.getMaskedValue());
    }

    // ---- results that carry no mask ----

    @Test
    void aNullResultStaysNull() {
        assertNull(StarRocksDataMasks.translate(null, STARROCKS));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "MASK_NONE", "mask_none"})
    void aResultWithoutAMaskIsUntouched(String maskType) {
        RangerAccessResult result = maskResult(maskType);

        assertSame(result, StarRocksDataMasks.translate(result, STARROCKS));
        assertEquals(maskType, result.getMaskType());
        assertFalse(result.isMaskEnabled());
    }

    // ---- unknown mask types fail closed ----

    @ParameterizedTest(name = "unknown type {0}")
    @ValueSource(strings = {"MASK_SHOW_LAST_2", "SOMETHING_ELSE"})
    void anUnknownMaskHidesTheColumn(String maskType) {
        RangerAccessResult result = translate(maskType);

        assertEquals(RangerPolicy.MASK_TYPE_NULL, result.getMaskType(),
            "an unsupported mask must hide the column, not expose it");
        assertNull(result.getMaskedValue());
    }

    /** a deployment whose definition lacks a type must not have it forwarded either */
    @Test
    void theServiceDefinitionDecidesWhatIsPassedThrough() {
        RangerServiceDef poorer = serviceDef("MASK_NULL");

        assertEquals(RangerPolicy.MASK_TYPE_NULL,
            StarRocksDataMasks.translate(maskResult("MASK"), poorer).getMaskType());
        assertEquals("MASK_NULL",
            StarRocksDataMasks.translate(maskResult("MASK_NULL"), poorer).getMaskType());
    }

    // ---- helpers ----

    private static RangerAccessResult translate(String maskType) {
        return StarRocksDataMasks.translate(maskResult(maskType), STARROCKS);
    }

    private static RangerServiceDef serviceDef(String... maskTypes) {
        List<RangerServiceDef.RangerDataMaskTypeDef> types = new ArrayList<>();
        for (String maskType : maskTypes) {
            RangerServiceDef.RangerDataMaskTypeDef type = new RangerServiceDef.RangerDataMaskTypeDef();
            type.setName(maskType);
            types.add(type);
        }

        RangerServiceDef.RangerDataMaskDef dataMaskDef = new RangerServiceDef.RangerDataMaskDef();
        dataMaskDef.setMaskTypes(types);

        RangerServiceDef serviceDef = new RangerServiceDef();
        serviceDef.setName("starrocks");
        serviceDef.setDataMaskDef(dataMaskDef);
        return serviceDef;
    }

    private static RangerAccessResult maskResult(String maskType) {
        RangerAccessResult result = new RangerAccessResult(
            RangerPolicy.POLICY_TYPE_DATAMASK, "dev_hive", null, null);
        result.setIsAccessDetermined(true);
        result.setIsAllowed(true);
        result.setMaskType(maskType);
        return result;
    }
}
