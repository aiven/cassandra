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

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import org.slf4j.Logger;

import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.locator.NetworkTopologyStrategy;
import org.apache.cassandra.locator.SimpleStrategy;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.schema.ReplicationParams;
import org.apache.cassandra.service.ClientWarn;


public class TuneUpReplicationFactor
{
    public static final String REPLICATION_FACTOR = "replication_factor";
    private final Logger logger;

    public TuneUpReplicationFactor(Logger logger)
    {
        this.logger = logger;
    }


    public Map<String, String> apply(String keyspaceName, KeyspaceParams inputKeyspaceParams)
    {
        final Map<String, String> replicationParams = inputKeyspaceParams.replication.asMap(); // new hashmap just created for client use, ok to modify
        if (replicationParams.get(ReplicationParams.CLASS).equals(SimpleStrategy.class.getName()) && (Integer.parseInt(replicationParams.get(REPLICATION_FACTOR)) < 2))
        {
            final String msg = String.format("Trying to use an insufficient replication factor for keyspace %s, will be automatically tuned up to 2", keyspaceName);
            logger.warn(msg);
            ClientWarn.instance.warn(msg);
            replicationParams.put(REPLICATION_FACTOR, "2");
        }
        else if (replicationParams.get(ReplicationParams.CLASS).equals(NetworkTopologyStrategy.class.getName()))
        {
            final int networkTopologyFullReplicasCount = replicationParams.entrySet().stream()
                                                                          .filter(e -> !e.getKey().equals(ReplicationParams.CLASS))
                                                                          .map(e -> {
                                                                              try
                                                                              {
                                                                                  return Optional.of(Integer.parseInt(e.getValue()));
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

                    int rf = Integer.parseInt(v);
                    replicationParams.put(k, String.format("%d", rf));
                    final String msg = String.format("Trying to use an insufficient replication factor for keyspace %s, DC '%s' will be automatically tuned up +1", keyspaceName, k);
                    logger.warn(msg);
                    ClientWarn.instance.warn(msg);
                    break;
                }
            }
        }

        return replicationParams;
    }
}
