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

package org.apache.cassandra.cql3.statements.schema;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.locator.NetworkTopologyStrategy;
import org.apache.cassandra.locator.ReplicationFactor;
import org.apache.cassandra.locator.SimpleStrategy;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.schema.ReplicationParams;
import org.apache.cassandra.schema.SchemaConstants;


public class TuneUpReplicationFactor
{

    public static final String REPLICATION_FACTOR = "replication_factor";

    public final static TuneUpReplicationFactor instance = new TuneUpReplicationFactor();

    public Map<String, String> apply(Logger logger, String keyspaceName, Set<String> clientWarnings, KeyspaceParams inputKeyspaceParams)
    {
        final Map<String, String> replicationParams = inputKeyspaceParams.replication.asMap(); // new hashmap just created for client use, ok to modify
        if (!DatabaseDescriptor.uptuningEnabled() || SchemaConstants.isReplicatedSystemKeyspace(keyspaceName) || SchemaConstants.isLocalSystemKeyspace(keyspaceName))
        {
            return replicationParams;
        }

        if (replicationParams.get(ReplicationParams.CLASS).equals(SimpleStrategy.class.getName()))
        {
            tuneUpSimpleStrategy(replicationParams, logger, clientWarnings, keyspaceName);
        }
        else if (replicationParams.get(ReplicationParams.CLASS).equals(NetworkTopologyStrategy.class.getName()))
        {
            tuneUpNetworkTopologyStrategy(replicationParams, logger, clientWarnings, keyspaceName);
        }

        // we don't need to tuneup localstrategy.

        return replicationParams;
    }

    public static void tuneUpSimpleStrategy(Map<String, String> replicationParams, Logger logger, Set<String> clientWarnings, String keyspaceName)
    {

        int currentReplicationFactor;
        try
        {
            currentReplicationFactor = Integer.parseInt(replicationParams.get(REPLICATION_FACTOR));
        }
        catch (NumberFormatException e)
        {
            return;
        }

        if (currentReplicationFactor < 2)
        {
            final String msg = String.format("Trying to use an insufficient replication factor for keyspace %s, will be automatically tuned up to 2", keyspaceName);
            logger.warn(msg);
            clientWarnings.add(msg);
            replicationParams.put(REPLICATION_FACTOR, "2");
        }
    }

    public static void tuneUpNetworkTopologyStrategy(Map<String, String> replicationParams, Logger logger, Set<String> clientWarnings, String keyspaceName)
    {
        final int networkTopologyFullReplicasCount = replicationParams.entrySet().stream()
                                                                      .filter(e -> !e.getKey().equals(ReplicationParams.CLASS))
                                                                      .map(e -> {
                                                                          try
                                                                          {
                                                                              final ReplicationFactor rf = ReplicationFactor.fromString(e.getValue());
                                                                              return Optional.of(rf.fullReplicas);
                                                                          }
                                                                          catch (IllegalArgumentException ex)
                                                                          {
                                                                              return Optional.<Integer>empty();
                                                                          }
                                                                      })
                                                                      .filter(
                                                                      Optional::isPresent)
                                                                      .mapToInt(Optional::get)
                                                                      .sum();

        if (networkTopologyFullReplicasCount < 2)
        {
            // we pick a dc we like for uptuning, at least make it consistent
            final TreeMap<String, String> sortedMap = new TreeMap<>(replicationParams);
            for (Map.Entry<String, String> keyValue : sortedMap.entrySet())
            {
                final String k = keyValue.getKey();
                final String v = keyValue.getValue();

                if (k.equals(ReplicationParams.CLASS))
                {
                    continue;
                }

                ReplicationFactor rf;
                try
                {
                    rf = ReplicationFactor.fromString(v);
                }
                catch (IllegalArgumentException e)
                {
                    logger.warn("Found unparseable replication factor {}", v);
                    continue;
                }
                final int tuneUpDelta = 2 - networkTopologyFullReplicasCount;
                replicationParams.put(k, String.format("%d/%d", rf.fullReplicas + tuneUpDelta, rf.transientReplicas()));
                final String msg = String.format("Trying to use an insufficient replication factor for keyspace %s, DC '%s' will be automatically tuned up to %d", keyspaceName, k, tuneUpDelta);
                logger.warn(msg);
                clientWarnings.add(msg);
                break;
            }
        }
    }
}
