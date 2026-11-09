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

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PaimonAuditMaintenanceTest {

    @Test
    public void testDisabledByDefaultDoesNotRun() {
        PaimonAuditMaintenance maintenance = new PaimonAuditMaintenance();
        maintenance.configureForTest(() -> {
            throw new AssertionError("must not run");
        }, false, 50);

        maintenance.start();
        try {
            assertFalse(maintenance.isRunning());
        } finally {
            maintenance.stop();
        }
    }

    @Test
    public void testEnabledRunsTaskPeriodicallyAndStops() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        PaimonAuditMaintenance maintenance = new PaimonAuditMaintenance();
        maintenance.configureForTest(runs::incrementAndGet, true, 50);

        maintenance.start();
        try {
            long deadline = System.currentTimeMillis() + 10_000L;
            while (runs.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue("task did not run", runs.get() >= 2);
            assertTrue(maintenance.isRunning());
        } finally {
            maintenance.stop();
        }
        Thread.sleep(150);
        int afterStop = runs.get();
        Thread.sleep(150);
        assertEquals("task must stop with the service", afterStop, runs.get());
    }
}
