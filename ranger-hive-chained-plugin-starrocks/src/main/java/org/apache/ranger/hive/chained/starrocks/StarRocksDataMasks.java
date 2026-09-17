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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.util.ServiceDefUtil;

/**
 * Rewrites the mask of a Hive data-mask result into one the StarRocks FE can apply.
 *
 * <p>The result carries the mask type of the Hive service, but the FE resolves it against the
 * StarRocks service definition and parses whatever transformer it finds there. A mask type that
 * definition does not know leaves the FE without a transformer, so it parses {@code null} and the
 * query fails; {@code MASK_HASH} fails the same way because its StarRocks transformer spells the
 * placeholder {@code {COL}} while the FE only substitutes {@code {col}}. Such a mask becomes a
 * {@link RangerPolicy#MASK_TYPE_CUSTOM} one carrying an equivalent StarRocks expression, and a mask
 * type with no equivalent becomes {@link RangerPolicy#MASK_TYPE_NULL}, hiding the column rather than
 * exposing it.
 */
@Slf4j
final class StarRocksDataMasks {
    static final String MASK_SHOW_LAST_4 = "MASK_SHOW_LAST_4";
    static final String MASK_SHOW_FIRST_4 = "MASK_SHOW_FIRST_4";
    static final String MASK_HASH = "MASK_HASH";

    /** Hive mask type -> StarRocks expression; the FE substitutes {@code {col}} and {@code {type}}. */
    static final Map<String, String> EXPRESSIONS = expressions();

    private StarRocksDataMasks() {
    }

    /**
     * Rewrites the mask of the given result in place.
     *
     * @param starRocksServiceDef service definition of the root plugin, which decides what the FE
     *                            can apply
     * @return the same result, or null if null was given
     */
    static RangerAccessResult translate(RangerAccessResult result, RangerServiceDef starRocksServiceDef) {
        if (result == null || !result.isMaskEnabled()) {
            return result;
        }

        String maskType = result.getMaskType();
        String expression = EXPRESSIONS.get(maskType.toUpperCase(Locale.ENGLISH));

        if (expression != null) {
            result.setMaskType(RangerPolicy.MASK_TYPE_CUSTOM);
            result.setMaskedValue(expression);
        } else if (ServiceDefUtil.getDataMaskType(starRocksServiceDef, maskType) == null) {
            log.warn("Hive mask type {} is not supported by StarRocks, masking the column with NULL", maskType);
            result.setMaskType(RangerPolicy.MASK_TYPE_NULL);
            result.setMaskedValue(null);
        }

        return result;
    }

    private static Map<String, String> expressions() {
        Map<String, String> expressions = new LinkedHashMap<>();

        expressions.put(MASK_SHOW_LAST_4,
            "cast(concat(repeat('x', greatest(char_length(cast({col} as string)) - 4, 0)), "
                + "right(cast({col} as string), 4)) as {type})");
        expressions.put(MASK_SHOW_FIRST_4,
            "cast(concat(left(cast({col} as string), 4), "
                + "repeat('x', greatest(char_length(cast({col} as string)) - 4, 0))) as {type})");
        // the StarRocks MASK_HASH transformer with the placeholder the FE actually substitutes
        expressions.put(MASK_HASH,
            "cast(hex(sha2(from_binary(to_binary({col}, 'utf8'), 'utf8'), 256)) as {type})");

        return Collections.unmodifiableMap(expressions);
    }
}
