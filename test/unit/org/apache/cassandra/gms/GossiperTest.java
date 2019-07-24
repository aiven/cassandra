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

package org.apache.cassandra.gms;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.RandomPartitioner;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.locator.SeedProvider;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.service.StorageService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GossiperTest
{
    @BeforeClass
    public static void before()
    {
        DatabaseDescriptor.daemonInitialization();
        SchemaLoader.prepareServer();
        SchemaLoader.createKeyspace("schema_test_ks",
                                    KeyspaceParams.simple(1),
                                    SchemaLoader.standardCFMD("schema_test_ks", "schema_test_cf"));
    }

    static final IPartitioner partitioner = new RandomPartitioner();
    StorageService ss = StorageService.instance;
    TokenMetadata tmd = StorageService.instance.getTokenMetadata();
    ArrayList<Token> endpointTokens = new ArrayList<>();
    ArrayList<Token> keyTokens = new ArrayList<>();
    List<InetAddress> hosts = new ArrayList<>();
    List<UUID> hostIds = new ArrayList<>();

    private SeedProvider originalSeedProvider;

    @Before
    public void setup()
    {
        tmd.clearUnsafe();
        originalSeedProvider = DatabaseDescriptor.getSeedProvider();
    }

    @After
    public void tearDown()
    {
        DatabaseDescriptor.setSeedProvider(originalSeedProvider);
    }

    @Test
    public void testLargeGenerationJump() throws UnknownHostException
    {
        Util.createInitialRing(ss, partitioner, endpointTokens, keyTokens, hosts, hostIds, 2);
        InetAddress remoteHostAddress = hosts.get(1);

        EndpointState initialRemoteState = Gossiper.instance.getEndpointStateForEndpoint(remoteHostAddress);
        HeartBeatState initialRemoteHeartBeat = initialRemoteState.getHeartBeatState();

        //Util.createInitialRing should have initialized remoteHost's HeartBeatState's generation to 1
        assertEquals(initialRemoteHeartBeat.getGeneration(), 1);

        HeartBeatState proposedRemoteHeartBeat = new HeartBeatState(initialRemoteHeartBeat.getGeneration() + Gossiper.MAX_GENERATION_DIFFERENCE + 1);
        EndpointState proposedRemoteState = new EndpointState(proposedRemoteHeartBeat);

        Gossiper.instance.applyStateLocally(ImmutableMap.of(remoteHostAddress, proposedRemoteState));

        //The generation should have been updated because it isn't over Gossiper.MAX_GENERATION_DIFFERENCE in the future
        HeartBeatState actualRemoteHeartBeat = Gossiper.instance.getEndpointStateForEndpoint(remoteHostAddress).getHeartBeatState();
        assertEquals(proposedRemoteHeartBeat.getGeneration(), actualRemoteHeartBeat.getGeneration());

        //Propose a generation 10 years in the future - this should be rejected.
        HeartBeatState badProposedRemoteHeartBeat = new HeartBeatState((int) (System.currentTimeMillis()/1000) + Gossiper.MAX_GENERATION_DIFFERENCE * 10);
        EndpointState badProposedRemoteState = new EndpointState(badProposedRemoteHeartBeat);

        Gossiper.instance.applyStateLocally(ImmutableMap.of(remoteHostAddress, badProposedRemoteState));

        actualRemoteHeartBeat = Gossiper.instance.getEndpointStateForEndpoint(remoteHostAddress).getHeartBeatState();

        //The generation should not have been updated because it is over Gossiper.MAX_GENERATION_DIFFERENCE in the future
        assertEquals(proposedRemoteHeartBeat.getGeneration(), actualRemoteHeartBeat.getGeneration());
    }

    @Test
    public void testSchemaVersionUpdate() throws UnknownHostException, InterruptedException
    {
        Util.createInitialRing(ss, partitioner, endpointTokens, keyTokens, hosts, hostIds, 2);
        MessagingService.instance().listen();
        Gossiper.instance.start(1);
        InetAddress remoteHostAddress = hosts.get(1);

        EndpointState initialRemoteState = Gossiper.instance.getEndpointStateForEndpoint(remoteHostAddress);
        // Set to any 3.0 version
        Gossiper.instance.injectApplicationState(remoteHostAddress, ApplicationState.RELEASE_VERSION, StorageService.instance.valueFactory.releaseVersion("3.0.14"));

        Gossiper.instance.applyStateLocally(ImmutableMap.of(remoteHostAddress, initialRemoteState));

        // wait until the schema is set
        VersionedValue schema = null;
        for (int i = 0; i < 10; i++)
        {
            EndpointState localState = Gossiper.instance.getEndpointStateForEndpoint(hosts.get(0));
            schema = localState.getApplicationState(ApplicationState.SCHEMA);
            if (schema != null)
                break;
            Thread.sleep(1000);
        }

        // schema is set and equals to "alternative" version
        assertTrue(schema != null);
        assertEquals(schema.value, Schema.instance.getAltVersion().toString());

        // Upgrade remote host version to the latest one (3.11)
        Gossiper.instance.injectApplicationState(remoteHostAddress, ApplicationState.RELEASE_VERSION, StorageService.instance.valueFactory.releaseVersion());

        Gossiper.instance.applyStateLocally(ImmutableMap.of(remoteHostAddress, initialRemoteState));

        // wait until the schema change
        VersionedValue newSchema = null;
        for (int i = 0; i < 10; i++)
        {
            EndpointState localState = Gossiper.instance.getEndpointStateForEndpoint(hosts.get(0));
            newSchema = localState.getApplicationState(ApplicationState.SCHEMA);
            if (!schema.value.equals(newSchema.value))
                break;
            Thread.sleep(1000);
        }

        // schema is changed and equals to real version
        assertFalse(schema.value.equals(newSchema.value));
        assertEquals(newSchema.value, Schema.instance.getRealVersion().toString());
    }

    // Note: This test might fail if for some reason the node broadcast address is in 127.99.0.0/16
    @Test
    public void testReloadSeeds() throws UnknownHostException
    {
        Gossiper gossiper = new Gossiper(false);
        List<String> loadedList;

        // Initialize the seed list directly to a known set to start with
        gossiper.seeds.clear();
        InetAddress addr = InetAddress.getByAddress(InetAddress.getByName("127.99.1.1").getAddress());
        int nextSize = 4;
        List<InetAddress> nextSeeds = new ArrayList<>(nextSize);
        for (int i = 0; i < nextSize; i ++)
        {
            gossiper.seeds.add(addr);
            nextSeeds.add(addr);
            addr = InetAddress.getByAddress(InetAddresses.increment(addr).getAddress());
        }
        Assert.assertEquals(nextSize, gossiper.seeds.size());

        // Add another unique address to the list
        addr = InetAddress.getByAddress(InetAddresses.increment(addr).getAddress());
        nextSeeds.add(addr);
        nextSize++;
        DatabaseDescriptor.setSeedProvider(new TestSeedProvider(nextSeeds));
        loadedList = gossiper.reloadSeeds();

        // Check that the new entry was added
        Assert.assertEquals(nextSize, loadedList.size());
        for (InetAddress a : nextSeeds)
            Assert.assertTrue(loadedList.contains(a.toString()));

        // Check that the return value of the reloadSeeds matches the content of the getSeeds call
        // and that they both match the internal contents of the Gossiper seeds list
        Assert.assertEquals(loadedList.size(), gossiper.getSeeds().size());
        for (InetAddress a : gossiper.seeds)
        {
            Assert.assertTrue(loadedList.contains(a.toString()));
            Assert.assertTrue(gossiper.getSeeds().contains(a.toString()));
        }

        // Add a duplicate of the last address to the seed provider list
        int uniqueSize = nextSize;
        nextSeeds.add(addr);
        nextSize++;
        DatabaseDescriptor.setSeedProvider(new TestSeedProvider(nextSeeds));
        loadedList = gossiper.reloadSeeds();

        // Check that the number of seed nodes reported hasn't increased
        Assert.assertEquals(uniqueSize, loadedList.size());
        for (InetAddress a : nextSeeds)
            Assert.assertTrue(loadedList.contains(a.toString()));

        // Create a new list that has no overlaps with the previous list
        addr = InetAddress.getByAddress(InetAddress.getByName("127.99.2.1").getAddress());
        int disjointSize = 3;
        List<InetAddress> disjointSeeds = new ArrayList<>(disjointSize);
        for (int i = 0; i < disjointSize; i ++)
        {
            disjointSeeds.add(addr);
            addr = InetAddress.getByAddress(InetAddresses.increment(addr).getAddress());
        }
        DatabaseDescriptor.setSeedProvider(new TestSeedProvider(disjointSeeds));
        loadedList = gossiper.reloadSeeds();

        // Check that the list now contains exactly the new other list.
        Assert.assertEquals(disjointSize, gossiper.getSeeds().size());
        Assert.assertEquals(disjointSize, loadedList.size());
        for (InetAddress a : disjointSeeds)
        {
            Assert.assertTrue(gossiper.getSeeds().contains(a.toString()));
            Assert.assertTrue(loadedList.contains(a.toString()));
        }

        // Set the seed node provider to return an empty list
        DatabaseDescriptor.setSeedProvider(new TestSeedProvider(new ArrayList<InetAddress>()));
        loadedList = gossiper.reloadSeeds();

        // Check that the in memory seed node list was not modified
        Assert.assertEquals(disjointSize, loadedList.size());
        for (InetAddress a : disjointSeeds)
            Assert.assertTrue(loadedList.contains(a.toString()));

        // Change the seed provider to one that throws an unchecked exception
        DatabaseDescriptor.setSeedProvider(new ErrorSeedProvider());
        loadedList = gossiper.reloadSeeds();

        // Check for the expected null response from a reload error
        Assert.assertNull(loadedList);

        // Check that the in memory seed node list was not modified and the exception was caught
        Assert.assertEquals(disjointSize, gossiper.getSeeds().size());
        for (InetAddress a : disjointSeeds)
            Assert.assertTrue(gossiper.getSeeds().contains(a.toString()));
    }

    static class TestSeedProvider implements SeedProvider
    {
        private List<InetAddress> seeds;

        TestSeedProvider(List<InetAddress> seeds)
        {
            this.seeds = seeds;
        }

        @Override
        public List<InetAddress> getSeeds()
        {
            return seeds;
        }
    }

    // A seed provider for testing which throws assertion errors when queried
    static class ErrorSeedProvider implements SeedProvider
    {
        @Override
        public List<InetAddress> getSeeds()
        {
            assert(false);
            return new ArrayList<>();
        }
    }
}
