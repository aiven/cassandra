/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.cql3.statements;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.helpers.NOPLogger;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.statements.schema.TuneUpReplicationFactor;
import org.apache.cassandra.schema.KeyspaceParams;

import static org.junit.Assert.assertEquals;

public class TuneUpReplicationFactorTest


{

    @BeforeClass
    public static void setUp()
    {
        DatabaseDescriptor.clientInitialization(false);
        DatabaseDescriptor.setTransientReplicationEnabledUnsafe(true);
    }


    @Test
    public void simpleStrategyWithLessThanRF2IsTunedUp()
    {
        // 0 doesn't actually make sense in Cassandra but we want to make sure this is working consistently
        IntStream.range(0, 2).forEach(n -> {
            final Map<String, String> replicationParams = new HashMap<String, String>()
            {
                {
                    put(TuneUpReplicationFactor.REPLICATION_FACTOR, Integer.toString(n));
                    put("class", "org.apache.cassandra.locator.SimpleStrategy");
                }
            };
            TuneUpReplicationFactor.tuneUpSimpleStrategy(replicationParams, NOPLogger.NOP_LOGGER, new HashSet<>(), "any");

            assertEquals("2", replicationParams.get(TuneUpReplicationFactor.REPLICATION_FACTOR));
        });
    }

    @Test
    public void simpleStrategyWithMissingReplicationFactorLeavesInputUnaltered()
    {
        final Map<String, String> replicationParams = new HashMap<String, String>()
        {
            {
                put("class", "org.apache.cassandra.locator.SimpleStrategy");
            }
        };
        TuneUpReplicationFactor.tuneUpSimpleStrategy(replicationParams, NOPLogger.NOP_LOGGER, new HashSet<>(), "any");

        assertEquals(1, replicationParams.size());
    }

    @Test
    public void simpleStrategyWithRFGreaterOrEqualThan2IsLeftUntouched()
    {

        IntStream.range(2, 20).forEach(n -> {
            final Map<String, String> replicationParams = new HashMap<String, String>()
            {
                {
                    put(TuneUpReplicationFactor.REPLICATION_FACTOR, Integer.toString(n));
                    put("class", "org.apache.cassandra.locator.SimpleStrategy");
                }
            };
            TuneUpReplicationFactor.tuneUpSimpleStrategy(replicationParams, NOPLogger.NOP_LOGGER, new HashSet<>(), "any");

            assertEquals(Integer.toString(n), replicationParams.get(TuneUpReplicationFactor.REPLICATION_FACTOR));
        });
    }

    @Test
    public void networkTopologyWithLessThanRF2IsTunedUpToAtLeastTwo()
    {
        // 0 doesn't actually make sense in Cassandra but we want to make sure this is working consistently
        final String DATACENTER = "datacenter";
        final int transientReplicas = 0; // cannot set greater fullReplicas

        IntStream.range(0, 2).forEach(fullReplicas -> {

            final Map<String, String> replicationParams = new HashMap<String, String>()
            {
                {
                    put(DATACENTER, String.format("%d/%d", fullReplicas, transientReplicas));
                    put("class", "org.apache.cassandra.locator.NetworkTopologyStrategy");
                }
            };
            TuneUpReplicationFactor.tuneUpNetworkTopologyStrategy(replicationParams, NOPLogger.NOP_LOGGER, new HashSet<>(), "any");

            assertEquals(String.format("2/%d", transientReplicas), replicationParams.get(DATACENTER));
        });
    }

    @Test
    public void networkTopologyWithLessThanRF2IsTunedUpToAtLeastTwoWithoutTransientReplicas()
    {
        // 0 doesn't actually make sense in Cassandra but we want to make sure this is working consistently
        final String DATACENTER = "datacenter";

        IntStream.range(0, 2).forEach(fullReplicas -> {

            final Map<String, String> replicationParams = new HashMap<String, String>()
            {
                {
                    put(DATACENTER, String.format("%d", fullReplicas));
                    put("class", "org.apache.cassandra.locator.NetworkTopologyStrategy");
                }
            };
            TuneUpReplicationFactor.tuneUpNetworkTopologyStrategy(replicationParams, NOPLogger.NOP_LOGGER, new HashSet<>(), "any");

            assertEquals(String.format("2/0"), replicationParams.get(DATACENTER));
        });
    }

    @Test
    public void networkTopologyWithRF2OrMoreIsUntouched()
    {
        // 0 doesn't actually make sense in Cassandra but we want to make sure this is working consistently
        final String DATACENTER = "datacenter";


        IntStream.range(2, 20).forEach(fullReplicas -> {
            final int transientReplicas = Math.min(0, fullReplicas - 1); // cannot set greater fullReplicas

            final Map<String, String> replicationParams = new HashMap<String, String>()
            {
                {
                    put(DATACENTER, String.format("%d/%d", fullReplicas, transientReplicas));
                    put("class", "org.apache.cassandra.locator.NetworkTopologyStrategy");
                }
            };
            TuneUpReplicationFactor.tuneUpNetworkTopologyStrategy(replicationParams, NOPLogger.NOP_LOGGER, new HashSet<>(), "any");

            assertEquals(String.format("%d/%d", fullReplicas, transientReplicas), replicationParams.get(DATACENTER));
        });
    }



    @Test
    public void networkTopologyWithRF2EvenInDifferentDataCentersIsUntouched()
    {
        // 0 doesn't actually make sense in Cassandra but we want to make sure this is working consistently
        final String DATACENTER1 = "datacenter1";
        final String DATACENTER2 = "datacenter2";


        final Map<String, String> replicationParams = new HashMap<String, String>()
        {
            {
                put(DATACENTER1, "1/0");
                put(DATACENTER2, "1/0");
                put("class", "org.apache.cassandra.locator.NetworkTopologyStrategy");
            }
        };
        TuneUpReplicationFactor.tuneUpNetworkTopologyStrategy(replicationParams, NOPLogger.NOP_LOGGER, new HashSet<>(), "any");

        assertEquals("1/0", replicationParams.get(DATACENTER1));
        assertEquals("1/0", replicationParams.get(DATACENTER2));
    }

    @Test
    public void networkTopologyWithRFLessThanOneLexicographicalFirstIsUptuned()
    {
        // 0 doesn't actually make sense in Cassandra but we want to make sure this is working consistently
        final String DATACENTER1 = "first";
        final String DATACENTER2 = "second";


        final Map<String, String> replicationParams = new HashMap<String, String>()
        {
            {
                put(DATACENTER1, "1/0");
                put(DATACENTER2, "0/0");
                put("class", "org.apache.cassandra.locator.NetworkTopologyStrategy");
            }
        };
        TuneUpReplicationFactor.tuneUpNetworkTopologyStrategy(replicationParams, NOPLogger.NOP_LOGGER, new HashSet<>(), "any");

        assertEquals("2/0", replicationParams.get(DATACENTER1));
        assertEquals("0/0", replicationParams.get(DATACENTER2));
    }

    @Test
    public void networkTopologyWithUnknownInputLeavesParamsUntouched()
    {
        // 0 doesn't actually make sense in Cassandra but we want to make sure this is working consistently
        final String DATACENTER1 = "first";

        final Map<String, String> replicationParams = new HashMap<String, String>()
        {
            {
                put(DATACENTER1, "FOOBAR");
                put("class", "org.apache.cassandra.locator.NetworkTopologyStrategy");
            }
        };
        TuneUpReplicationFactor.tuneUpNetworkTopologyStrategy(replicationParams, NOPLogger.NOP_LOGGER, new HashSet<>(), "any");

        assertEquals("FOOBAR", replicationParams.get(DATACENTER1));
    }

    @Test
    public void unknownInputParamsAreIgnored()
    {
        final Map<String, String> replicationParams = new HashMap<String, String>()
        {
            {
                put("foo", "bar");
                put("klass", "something");
            }
        };
        TuneUpReplicationFactor.tuneUpNetworkTopologyStrategy(replicationParams, NOPLogger.NOP_LOGGER, new HashSet<>(), "any");

        assertEquals("bar", replicationParams.get("foo"));
        assertEquals("something", replicationParams.get("klass"));
    }

    @Test
    public void uptuningIsAppliedToNonSystemKeyspaces()
    {
        // 0 doesn't actually make sense in Cassandra but we want to make sure this is working consistently
        final String DATACENTER1 = "first";

        final KeyspaceParams keyspaceParams = KeyspaceParams.create(true, new HashMap<String, String>()
        {
            {
                put(DATACENTER1, "1/0");
                put("class", "org.apache.cassandra.locator.NetworkTopologyStrategy");
            }
        });
        Map<String, String> somekeyspaceUptuned = new TuneUpReplicationFactor().apply(NOPLogger.NOP_LOGGER, "somekeyspace", new HashSet<>(), keyspaceParams);
        assertEquals("2/0", somekeyspaceUptuned.get(DATACENTER1));
    }

    @Test
    public void uptuningIsNotAppliedToSystemKeyspaces()
    {
        // 0 doesn't actually make sense in Cassandra but we want to make sure this is working consistently
        final String DATACENTER1 = "first";

        final KeyspaceParams keyspaceParams = KeyspaceParams.create(true, new HashMap<String, String>()
        {
            {
                put(DATACENTER1, "1/0");
                put("class", "org.apache.cassandra.locator.NetworkTopologyStrategy");
            }
        });
        Map<String, String> somekeyspaceUptuned = new TuneUpReplicationFactor().apply(NOPLogger.NOP_LOGGER, "system", new HashSet<>(), keyspaceParams);
        assertEquals("1/0", somekeyspaceUptuned.get(DATACENTER1));
    }

    @Test
    public void uptuningIsAppliedToSimpleStrategy()
    {
        // 0 doesn't actually make sense in Cassandra but we want to make sure this is working consistently

        final KeyspaceParams keyspaceParams = KeyspaceParams.create(true, new HashMap<String, String>()
        {
            {
                put(TuneUpReplicationFactor.REPLICATION_FACTOR, "1");
                put("class", "org.apache.cassandra.locator.SimpleStrategy");
            }
        });
        Map<String, String> somekeyspaceUptuned = new TuneUpReplicationFactor().apply(NOPLogger.NOP_LOGGER, "somekeyspace", new HashSet<>(), keyspaceParams);
        assertEquals("2", somekeyspaceUptuned.get(TuneUpReplicationFactor.REPLICATION_FACTOR));
    }

    @Test
    public void uptuningIsNotAppliedToLocalStrategy()
    {
        final KeyspaceParams keyspaceParams = KeyspaceParams.create(true, new HashMap<String, String>()
        {
            {
                put(TuneUpReplicationFactor.REPLICATION_FACTOR, "1");
                put("class", "org.apache.cassandra.locator.LocalStrategy");
            }
        });
        Map<String, String> somekeyspaceUptuned = new TuneUpReplicationFactor().apply(NOPLogger.NOP_LOGGER, "somekeyspace", new HashSet<>(), keyspaceParams);
        assertEquals("1", somekeyspaceUptuned.get(TuneUpReplicationFactor.REPLICATION_FACTOR));
    }
}
