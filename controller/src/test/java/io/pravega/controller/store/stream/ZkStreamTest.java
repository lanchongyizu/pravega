/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.controller.store.stream;

import com.google.common.collect.ImmutableMap;
import io.pravega.common.Exceptions;
import io.pravega.common.concurrent.ExecutorServiceHelpers;
import io.pravega.common.concurrent.Futures;
import io.pravega.controller.store.stream.tables.Data;
import io.pravega.controller.store.stream.tables.EpochTransitionRecord;
import io.pravega.test.common.AssertExtensions;
import io.pravega.test.common.TestingServerStarter;
import io.pravega.controller.store.stream.tables.State;
import io.pravega.controller.stream.api.grpc.v1.Controller.CreateScopeStatus;
import io.pravega.controller.stream.api.grpc.v1.Controller.DeleteScopeStatus;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.StreamConfiguration;
import com.google.common.collect.Lists;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.pravega.shared.segment.StreamSegmentNameUtils.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.reset;

public class ZkStreamTest {
    private static final String SCOPE = "scope";
    private TestingServer zkTestServer;
    private CuratorFramework cli;
    private StreamMetadataStore storePartialMock;

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(10);

    @Before
    public void startZookeeper() throws Exception {
        zkTestServer = new TestingServerStarter().start();
        cli = CuratorFrameworkFactory.newClient(zkTestServer.getConnectString(), new RetryOneTime(2000));
        cli.start();

        storePartialMock = Mockito.spy(new ZKStreamMetadataStore(cli, executor));
    }

    @After
    public void stopZookeeper() throws Exception {
        cli.close();
        zkTestServer.close();
        ExecutorServiceHelpers.shutdown(executor);
    }

    @Test
    public void testZkConnectionLoss() throws Exception {
        final ScalingPolicy policy = ScalingPolicy.fixed(5);

        final String streamName = "testfail";

        final StreamConfiguration streamConfig = StreamConfiguration.builder().scope(streamName).streamName(streamName).scalingPolicy(policy).build();

        zkTestServer.stop();

        try {
            storePartialMock.createStream(SCOPE, streamName, streamConfig, System.currentTimeMillis(), null, executor).get();
        } catch (ExecutionException e) {
            assert e.getCause() instanceof StoreException.StoreConnectionException;
        }
        zkTestServer.start();
    }

    @Test
    public void testCreateStreamState() throws Exception {
        final ScalingPolicy policy = ScalingPolicy.fixed(5);

        final StreamMetadataStore store = new ZKStreamMetadataStore(cli, executor);
        final String streamName = "testfail";

        StreamConfiguration streamConfig = StreamConfiguration.builder()
                .scope(streamName)
                .streamName(streamName)
                .scalingPolicy(policy)
                .build();

        store.createScope(SCOPE).get();
        store.createStream(SCOPE, streamName, streamConfig, System.currentTimeMillis(), null, executor).get();

        try {
            store.getConfiguration(SCOPE, streamName, null, executor).get();
        } catch (Exception e) {
            assert e.getCause() != null && e.getCause() instanceof IllegalStateException;
        }
        store.deleteScope(SCOPE);
    }

    @Test
    public void testZkCreateScope() throws Exception {

        // create new scope test
        final StreamMetadataStore store = new ZKStreamMetadataStore(cli, executor);
        final String scopeName = "Scope1";
        CompletableFuture<CreateScopeStatus> createScopeStatus = store.createScope(scopeName);

        // createScope returns null on success, and exception on failure
        assertEquals("Create new scope :", CreateScopeStatus.Status.SUCCESS, createScopeStatus.get().getStatus());

        // create duplicate scope test
        createScopeStatus = store.createScope(scopeName);
        assertEquals("Create new scope :", CreateScopeStatus.Status.SCOPE_EXISTS, createScopeStatus.get().getStatus());

        //listStreamsInScope test
        final String streamName1 = "Stream1";
        final String streamName2 = "Stream2";
        final ScalingPolicy policy = ScalingPolicy.fixed(5);
        StreamConfiguration streamConfig =
                StreamConfiguration.builder().scope(scopeName).streamName(streamName1).scalingPolicy(policy).build();

        StreamConfiguration streamConfig2 =
                StreamConfiguration.builder().scope(scopeName).streamName(streamName2).scalingPolicy(policy).build();

        store.createStream(scopeName, streamName1, streamConfig, System.currentTimeMillis(), null, executor).get();
        store.setState(scopeName, streamName1, State.ACTIVE, null, executor).get();
        store.createStream(scopeName, streamName2, streamConfig2, System.currentTimeMillis(), null, executor).get();
        store.setState(scopeName, streamName2, State.ACTIVE, null, executor).get();

        List<StreamConfiguration> listOfStreams = store.listStreamsInScope(scopeName).get();
        assertEquals("Size of list", 2, listOfStreams.size());
        assertEquals("Name of stream at index zero", "Stream1", listOfStreams.get(0).getStreamName());
        assertEquals("Name of stream at index one", "Stream2", listOfStreams.get(1).getStreamName());
    }

    @Test
    public void testZkDeleteScope() throws Exception {
        // create new scope
        final StreamMetadataStore store = new ZKStreamMetadataStore(cli, executor);
        final String scopeName = "Scope1";
        store.createScope(scopeName).get();

        // Delete empty scope Scope1
        CompletableFuture<DeleteScopeStatus> deleteScopeStatus = store.deleteScope(scopeName);
        assertEquals("Delete Empty Scope", DeleteScopeStatus.Status.SUCCESS, deleteScopeStatus.get().getStatus());

        // Delete non-existent scope Scope2
        CompletableFuture<DeleteScopeStatus> deleteScopeStatus2 = store.deleteScope("Scope2");
        assertEquals("Delete non-existent Scope", DeleteScopeStatus.Status.SCOPE_NOT_FOUND, deleteScopeStatus2.get().getStatus());

        // Delete non-empty scope Scope3
        store.createScope("Scope3").get();
        final ScalingPolicy policy = ScalingPolicy.fixed(5);
        final StreamConfiguration streamConfig =
                StreamConfiguration.builder().scope("Scope3").streamName("Stream3").scalingPolicy(policy).build();

        store.createStream("Scope3", "Stream3", streamConfig, System.currentTimeMillis(), null, executor).get();
        store.setState("Scope3", "Stream3", State.ACTIVE, null, executor).get();

        CompletableFuture<DeleteScopeStatus> deleteScopeStatus3 = store.deleteScope("Scope3");
        assertEquals("Delete non-empty Scope", DeleteScopeStatus.Status.SCOPE_NOT_EMPTY,
                deleteScopeStatus3.get().getStatus());
    }

    @Test
    public void testGetScope() throws Exception {
        final StreamMetadataStore store = new ZKStreamMetadataStore(cli, executor);
        final String scope1 = "Scope1";
        final String scope2 = "Scope2";
        String scopeName;

        // get existent scope
        store.createScope(scope1).get();
        scopeName = store.getScopeConfiguration(scope1).get();
        assertEquals("Get existent scope", scope1, scopeName);

        // get non-existent scope
        try {
            store.getScopeConfiguration(scope2).get();
        } catch (ExecutionException e) {
            assertTrue("Get non existent scope", e.getCause() instanceof StoreException.DataNotFoundException);
        }
    }

    @Test
    public void testZkListScope() throws Exception {
        // list scope test
        final StreamMetadataStore store = new ZKStreamMetadataStore(cli, executor);
        store.createScope("Scope1").get();
        store.createScope("Scope2").get();
        store.createScope("Scope3").get();

        List<String> listScopes = store.listScopes().get();
        assertEquals("List Scopes ", 3, listScopes.size());

        store.deleteScope("Scope3").get();
        listScopes = store.listScopes().get();
        assertEquals("List Scopes ", 2, listScopes.size());
    }

    @Test
    public void testZkStream() throws Exception {
        double keyChunk = 1.0 / 5;
        final ScalingPolicy policy = ScalingPolicy.fixed(5);

        final StreamMetadataStore store = new ZKStreamMetadataStore(cli, executor);
        final String streamName = "test";
        store.createScope(SCOPE).get();

        StreamConfiguration streamConfig = StreamConfiguration.builder()
                .scope(streamName)
                .streamName(streamName)
                .scalingPolicy(policy)
                .build();

        store.createStream(SCOPE, streamName, streamConfig, System.currentTimeMillis(), null, executor).get();
        store.setState(SCOPE, streamName, State.ACTIVE, null, executor).get();
        OperationContext context = store.createContext(SCOPE, streamName);

        List<Segment> segments = store.getActiveSegments(SCOPE, streamName, context, executor).get();
        assertEquals(segments.size(), 5);
        assertTrue(segments.stream().allMatch(x -> Lists.newArrayList(0L, 1L, 2L, 3L, 4L).contains(x.segmentId())));

        long start = segments.get(0).getStart();

        assertEquals(store.getConfiguration(SCOPE, streamName, context, executor).get(), streamConfig);

        List<AbstractMap.SimpleEntry<Double, Double>> newRanges;

        // existing range 0 = 0 - .2, 1 = .2 - .4, 2 = .4 - .6, 3 = .6 - .8, 4 = .8 - 1.0

        // 3, 4 -> 5 = .6 - 1.0
        newRanges = Collections.singletonList(
                new AbstractMap.SimpleEntry<>(3 * keyChunk, 1.0));

        long scale1 = start + 10000;
        ArrayList<Long> sealedSegments = Lists.newArrayList(3L, 4L);
        long five = computeSegmentId(5, 1);
        EpochTransitionRecord response = store.startScale(SCOPE, streamName, sealedSegments, newRanges, scale1, false, context, executor).get();
        ImmutableMap<Long, AbstractMap.SimpleEntry<Double, Double>> newSegments = response.getNewSegmentsWithRange();
        store.setState(SCOPE, streamName, State.SCALING, null, executor).join();
        store.scaleCreateNewSegments(SCOPE, streamName, false, context, executor).get();
        store.scaleNewSegmentsCreated(SCOPE, streamName, context, executor).get();
        store.scaleSegmentsSealed(SCOPE, streamName, sealedSegments.stream().collect(Collectors.toMap(x -> x, x -> 0L)),
                context, executor).get();
        store.setState(SCOPE, streamName, State.ACTIVE, null, executor).join();
        segments = store.getActiveSegments(SCOPE, streamName, context, executor).get();
        assertEquals(segments.size(), 4);
        assertTrue(segments.stream().allMatch(x -> Lists.newArrayList(0L, 1L, 2L, five).contains(x.segmentId())));

        // 1 -> 6 = 0.2 -.3, 7 = .3 - .4
        // 2,5 -> 8 = .4 - 1.0
        newRanges = Arrays.asList(
                new AbstractMap.SimpleEntry<>(keyChunk, 0.3),
                new AbstractMap.SimpleEntry<>(0.3, 2 * keyChunk),
                new AbstractMap.SimpleEntry<>(2 * keyChunk, 1.0));

        long scale2 = scale1 + 10000;
        ArrayList<Long> sealedSegments1 = Lists.newArrayList(1L, 2L, five);
        long six = computeSegmentId(6, 2);
        long seven = computeSegmentId(7, 2);
        long eight = computeSegmentId(8, 2);
        response = store.startScale(SCOPE, streamName, sealedSegments1, newRanges, scale2, false, context, executor).get();
        ImmutableMap<Long, AbstractMap.SimpleEntry<Double, Double>> segmentsCreated = response.getNewSegmentsWithRange();
        store.setState(SCOPE, streamName, State.SCALING, null, executor).join();
        store.scaleCreateNewSegments(SCOPE, streamName, false, context, executor).get();
        store.scaleNewSegmentsCreated(SCOPE, streamName, context, executor).get();
        store.scaleSegmentsSealed(SCOPE, streamName, sealedSegments1.stream().collect(Collectors.toMap(x -> x, x -> 0L)),
                context, executor).get();
        store.setState(SCOPE, streamName, State.ACTIVE, null, executor).join();

        segments = store.getActiveSegments(SCOPE, streamName, context, executor).get();
        assertEquals(segments.size(), 4);
        assertTrue(segments.stream().allMatch(x -> Lists.newArrayList(0L, six, seven, eight).contains(x.segmentId())));

        // 7 -> 9 = .3 - .35, 10 = .35 - .6
        // 8 -> 10 = .35 - .6, 11 = .6 - 1.0
        newRanges = Arrays.asList(
                new AbstractMap.SimpleEntry<>(0.3, 0.35),
                new AbstractMap.SimpleEntry<>(0.35, 3 * keyChunk),
                new AbstractMap.SimpleEntry<>(3 * keyChunk, 1.0));

        long scale3 = scale2 + 10000;
        long nine = computeSegmentId(9, 3);
        long ten = computeSegmentId(10, 3);
        long eleven = computeSegmentId(11, 3);
        ArrayList<Long> sealedSegments2 = Lists.newArrayList(seven, eight);
        response = store.startScale(SCOPE, streamName, sealedSegments2, newRanges, scale3, false, context, executor).get();
        segmentsCreated = response.getNewSegmentsWithRange();
        store.setState(SCOPE, streamName, State.SCALING, null, executor).join();
        store.scaleCreateNewSegments(SCOPE, streamName, false, context, executor).get();
        store.scaleNewSegmentsCreated(SCOPE, streamName, context, executor).get();
        store.scaleSegmentsSealed(SCOPE, streamName, sealedSegments2.stream().collect(Collectors.toMap(x -> x, x -> 0L)),
                context, executor).get();
        store.setState(SCOPE, streamName, State.ACTIVE, null, executor).join();

        segments = store.getActiveSegments(SCOPE, streamName, context, executor).get();
        assertEquals(segments.size(), 5);
        assertTrue(segments.stream().allMatch(x -> Lists.newArrayList(0L, six, nine, ten, eleven).contains(x.segmentId())));

        Map<Long, List<Long>> successors = store.getSuccessors(SCOPE, streamName, 0L, context, executor).get();
        assertTrue(successors.isEmpty());
        successors = store.getSuccessors(SCOPE, streamName, 1L, context, executor).get();
        assertTrue(successors.size() == 2 &&
                successors.containsKey(six) && successors.get(six).containsAll(Collections.singleton(1L)) &&
                successors.containsKey(seven) && successors.get(seven).containsAll(Collections.singleton(1L)));

        successors = store.getSuccessors(SCOPE, streamName, 2L, context, executor).get();
        assertTrue(successors.size() == 1 &&
                successors.containsKey(eight) && successors.get(eight).containsAll(Lists.newArrayList(2L, five)));

        successors = store.getSuccessors(SCOPE, streamName, 3L, context, executor).get();
        assertTrue(successors.size() == 1 &&
                successors.containsKey(five) && successors.get(five).containsAll(Lists.newArrayList(3L, 4L)));

        successors = store.getSuccessors(SCOPE, streamName, 4L, context, executor).get();
        assertTrue(successors.size() == 1 &&
                successors.containsKey(five) && successors.get(five).containsAll(Lists.newArrayList(3L, 4L)));

        successors = store.getSuccessors(SCOPE, streamName, five, context, executor).get();
        assertTrue(successors.size() == 1 &&
                successors.containsKey(eight) && successors.get(eight).containsAll(Lists.newArrayList(2L, five)));

        successors = store.getSuccessors(SCOPE, streamName, six, context, executor).get();
        assertTrue(successors.isEmpty());
        successors = store.getSuccessors(SCOPE, streamName, seven, context, executor).get();
        assertTrue(successors.size() == 2 &&
                successors.containsKey(nine) && successors.get(nine).containsAll(Collections.singleton(seven)) &&
                successors.containsKey(ten) && successors.get(ten).containsAll(Lists.newArrayList(seven, eight)));
        successors = store.getSuccessors(SCOPE, streamName, eight, context, executor).get();
        assertTrue(successors.size() == 2 &&
                successors.containsKey(eleven) && successors.get(eleven).containsAll(Collections.singleton(eight)) &&
                successors.containsKey(ten) && successors.get(ten).containsAll(Lists.newArrayList(seven, eight)));
        successors = store.getSuccessors(SCOPE, streamName, nine, context, executor).get();
        assertTrue(successors.isEmpty());
        successors = store.getSuccessors(SCOPE, streamName, ten, context, executor).get();
        assertTrue(successors.isEmpty());
        successors = store.getSuccessors(SCOPE, streamName, eleven, context, executor).get();
        assertTrue(successors.isEmpty());
        // start -1
        Map<Long, Long> historicalSegments = store.getActiveSegments(SCOPE, streamName, start - 1, context, executor).get();
        assertEquals(historicalSegments.size(), 5);
        assertTrue(historicalSegments.keySet().containsAll(Lists.newArrayList(0L, 1L, 2L, 3L, 4L)));

        // start + 1
        historicalSegments = store.getActiveSegments(SCOPE, streamName, start + 1, context, executor).get();
        assertEquals(historicalSegments.size(), 5);
        assertTrue(historicalSegments.keySet().containsAll(Lists.newArrayList(0L, 1L, 2L, 3L, 4L)));

        // scale1 + 1
        historicalSegments = store.getActiveSegments(SCOPE, streamName, scale1 + 1000, context, executor).get();
        assertEquals(historicalSegments.size(), 4);
        assertTrue(historicalSegments.keySet().containsAll(Lists.newArrayList(0L, 1L, 2L, five)));

        // scale2 + 1
        historicalSegments = store.getActiveSegments(SCOPE, streamName, scale2 + 1000, context, executor).get();
        assertEquals(historicalSegments.size(), 4);
        assertTrue(historicalSegments.keySet().containsAll(Lists.newArrayList(0L, six, seven, eight)));

        // scale3 + 1
        historicalSegments = store.getActiveSegments(SCOPE, streamName, scale3 + 1000, context, executor).get();
        assertEquals(historicalSegments.size(), 5);
        assertTrue(historicalSegments.keySet().containsAll(Lists.newArrayList(0L, six, nine, ten, eleven)));

        // scale 3 + 10000
        historicalSegments = store.getActiveSegments(SCOPE, streamName, scale3 + 10000, context, executor).get();
        assertEquals(historicalSegments.size(), 5);
        assertTrue(historicalSegments.keySet().containsAll(Lists.newArrayList(0L, six, nine, ten, eleven)));

        assertFalse(store.isSealed(SCOPE, streamName, context, executor).get());
        assertNotEquals(0, store.getActiveSegments(SCOPE, streamName, context, executor).get().size());
        Boolean sealOperationStatus = store.setSealed(SCOPE, streamName, context, executor).get();
        assertTrue(sealOperationStatus);
        assertTrue(store.isSealed(SCOPE, streamName, context, executor).get());
        assertEquals(0, store.getActiveSegments(SCOPE, streamName, context, executor).get().size());

        //seal an already sealed stream.
        Boolean sealOperationStatus1 = store.setSealed(SCOPE, streamName, context, executor).get();
        assertTrue(sealOperationStatus1);
        assertTrue(store.isSealed(SCOPE, streamName, context, executor).get());
        assertEquals(0, store.getActiveSegments(SCOPE, streamName, context, executor).get().size());

        //seal a non existing stream.
        try {
            store.setSealed(SCOPE, "nonExistentStream", null, executor).get();
        } catch (Exception e) {
            assertEquals(StoreException.DataNotFoundException.class, e.getCause().getClass());
        }

        store.markCold(SCOPE, streamName, 0L, System.currentTimeMillis() + 1000, null, executor).get();
        assertTrue(store.isCold(SCOPE, streamName, 0L, null, executor).get());
        Thread.sleep(1000);
        assertFalse(store.isCold(SCOPE, streamName, 0L, null, executor).get());

        store.markCold(SCOPE, streamName, 0L, System.currentTimeMillis() + 1000, null, executor).get();
        store.removeMarker(SCOPE, streamName, 0L, null, executor).get();

        assertFalse(store.isCold(SCOPE, streamName, 0L, null, executor).get());
    }

    @Test(timeout = 10000)
    public void testTransaction() throws Exception {
        final ScalingPolicy policy = ScalingPolicy.fixed(5);

        final StreamMetadataStore store = new ZKStreamMetadataStore(cli, executor);
        final String streamName = "testTx";
        store.createScope(SCOPE).get();
        final Predicate<Throwable> operationNotAllowedPredicate =
                ex -> Exceptions.unwrap(ex) instanceof StoreException.IllegalStateException;

        StreamConfiguration streamConfig = StreamConfiguration.builder()
                .scope(SCOPE)
                .streamName(streamName)
                .scalingPolicy(policy)
                .build();

        store.createStream(SCOPE, streamName, streamConfig, System.currentTimeMillis(), null, executor).get();
        store.setState(SCOPE, streamName, State.ACTIVE, null, executor).get();

        OperationContext context = store.createContext(ZkStreamTest.SCOPE, streamName);

        UUID txnId1 = store.generateTransactionId(SCOPE, streamName, null, executor).join();
        VersionedTransactionData tx = store.createTransaction(SCOPE, streamName, txnId1, 10000, 600000,
                context, executor).get();
        Assert.assertEquals(txnId1, tx.getId());

        UUID txnId2 = store.generateTransactionId(SCOPE, streamName, null, executor).join();
        VersionedTransactionData tx2 = store.createTransaction(SCOPE, streamName, txnId2, 10000, 600000,
                context, executor).get();
        Assert.assertEquals(txnId2, tx2.getId());

        store.sealTransaction(SCOPE, streamName, tx.getId(), true, Optional.<Integer>empty(),
                context, executor).get();
        assert store.transactionStatus(SCOPE, streamName, tx.getId(), context, executor)
                .get().equals(TxnStatus.COMMITTING);

        // Test to ensure that sealTransaction is idempotent.
        Assert.assertEquals(TxnStatus.COMMITTING, store.sealTransaction(SCOPE, streamName, tx.getId(), true,
                Optional.empty(), context, executor).join().getKey());

        // Test to ensure that COMMITTING_TXN transaction cannot be aborted.
        testAbortFailure(store, SCOPE, streamName, tx.getEpoch(), tx.getId(), context, operationNotAllowedPredicate);

        store.setState(SCOPE, streamName, State.COMMITTING_TXN, context, executor).join();
        CompletableFuture<TxnStatus> f1 = store.commitTransaction(SCOPE, streamName, tx.getId(), context, executor);
        store.setState(SCOPE, streamName, State.ACTIVE, context, executor).join();

        store.sealTransaction(SCOPE, streamName, tx2.getId(), false, Optional.<Integer>empty(),
                context, executor).get();
        assert store.transactionStatus(SCOPE, streamName, tx2.getId(), context, executor)
                .get().equals(TxnStatus.ABORTING);

        // Test to ensure that sealTransaction is idempotent.
        Assert.assertEquals(TxnStatus.ABORTING, store.sealTransaction(SCOPE, streamName, tx2.getId(), false,
                Optional.empty(), context, executor).join().getKey());

        // Test to ensure that ABORTING transaction cannot be committed.
        testCommitFailure(store, SCOPE, streamName, tx2.getEpoch(), tx2.getId(), context, operationNotAllowedPredicate);

        CompletableFuture<TxnStatus> f2 = store.abortTransaction(SCOPE, streamName, tx2.getId(), context, executor);

        CompletableFuture.allOf(f1, f2).get();

        assert store.transactionStatus(SCOPE, streamName, tx.getId(), context, executor)
                .get().equals(TxnStatus.COMMITTED);
        assert store.transactionStatus(SCOPE, streamName, tx2.getId(), context, executor)
                .get().equals(TxnStatus.ABORTED);

        // Test to ensure that sealTransaction, to commit it, on committed transaction does not throw an error.
        Assert.assertEquals(TxnStatus.COMMITTED, store.sealTransaction(SCOPE, streamName, tx.getId(), true,
                Optional.empty(), context, executor).join().getKey());

        // Test to ensure that commitTransaction is idempotent.
        store.setState(SCOPE, streamName, State.COMMITTING_TXN, context, executor).join();
        Assert.assertEquals(TxnStatus.COMMITTED,
                store.commitTransaction(SCOPE, streamName, tx.getId(), context, executor).join());
        store.setState(SCOPE, streamName, State.ACTIVE, context, executor).join();

        // Test to ensure that sealTransaction, to abort it, and abortTransaction on committed transaction throws error.
        testAbortFailure(store, SCOPE, streamName, tx.getEpoch(), tx.getId(), context, operationNotAllowedPredicate);

        // Test to ensure that sealTransaction, to abort it, on aborted transaction does not throw an error.
        Assert.assertEquals(TxnStatus.ABORTED, store.sealTransaction(SCOPE, streamName, tx2.getId(), false,
                Optional.empty(), context, executor).join().getKey());

        // Test to ensure that abortTransaction is idempotent.
        Assert.assertEquals(TxnStatus.ABORTED,
                store.abortTransaction(SCOPE, streamName, tx2.getId(), context, executor).join());

        // Test to ensure that sealTransaction, to abort it, and abortTransaction on committed transaction throws error.
        testCommitFailure(store, SCOPE, streamName, tx2.getEpoch(), tx2.getId(), context, operationNotAllowedPredicate);

        store.setState(SCOPE, streamName, State.COMMITTING_TXN, context, executor).join();
        assert store.commitTransaction(ZkStreamTest.SCOPE, streamName, UUID.randomUUID(), null, executor)
                .handle((ok, ex) -> {
                    if (ex.getCause() instanceof StoreException.DataNotFoundException) {
                        return true;
                    } else {
                        throw new RuntimeException("assert failed");
                    }
                }).get();
        store.setState(SCOPE, streamName, State.ACTIVE, context, executor).join();

        assert store.abortTransaction(ZkStreamTest.SCOPE, streamName, UUID.randomUUID(), null, executor)
                .handle((ok, ex) -> {
                    if (ex.getCause() instanceof StoreException.DataNotFoundException) {
                        return true;
                    } else {
                        throw new RuntimeException("assert failed");
                    }
                }).get();

        assert store.transactionStatus(ZkStreamTest.SCOPE, streamName, UUID.randomUUID(), context, executor)
                .get().equals(TxnStatus.UNKNOWN);
    }

    @Test(timeout = 10000)
    public void testGetActiveTxn() throws Exception {
        ZKStoreHelper storeHelper = spy(new ZKStoreHelper(cli, executor));
        ZKStream stream = new ZKStream("scope", "stream", storeHelper);

        storeHelper.createZNodeIfNotExist("/store/scope").join();
        final ScalingPolicy policy1 = ScalingPolicy.fixed(2);
        final StreamConfiguration configuration1 = StreamConfiguration.builder()
                .scope("scope").streamName("stream").scalingPolicy(policy1).build();
        stream.create(configuration1, System.currentTimeMillis()).join();
        stream.updateState(State.ACTIVE).join();
        UUID txId = stream.generateNewTxnId(0, 0L).join();
        stream.createTransaction(txId, 1000L, 1000L).join();

        String activeTxPath = stream.getActiveTxPath(0, txId.toString());
        // throw DataNotFoundException for txn path
        doReturn(Futures.failedFuture(StoreException.create(StoreException.Type.DATA_NOT_FOUND, "txn data not found")))
                .when(storeHelper).getData(eq(activeTxPath));

        Map<String, Data<Integer>> result = stream.getCurrentTxns().join();
        // verify that call succeeds and no active txns were found
        assertTrue(result.isEmpty());

        // throw generic exception for txn path
        doReturn(Futures.failedFuture(new RuntimeException())).when(storeHelper).getData(eq(activeTxPath));

        ZKStream stream2 = new ZKStream("scope", "stream", storeHelper);
        // verify that the call fails
        AssertExtensions.assertThrows("", stream2.getCurrentTxns(), e -> Exceptions.unwrap(e) instanceof RuntimeException);

        reset(storeHelper);
        ZKStream stream3 = new ZKStream("scope", "stream", storeHelper);
        result = stream3.getCurrentTxns().join();
        assertEquals(1, result.size());
    }

    private void testCommitFailure(StreamMetadataStore store, String scope, String stream, int epoch, UUID txnId,
                                   OperationContext context,
                                   Predicate<Throwable> checker) {
        AssertExtensions.assertThrows("Seal txn to commit it failure",
                () -> store.sealTransaction(scope, stream, txnId, true, Optional.empty(), context, executor),
                checker);

        AssertExtensions.assertThrows("Commit txn failure",
                () -> store.commitTransaction(scope, stream, txnId, context, executor),
                checker);
    }

    private void testAbortFailure(StreamMetadataStore store, String scope, String stream, int epoch, UUID txnId,
                                  OperationContext context,
                                  Predicate<Throwable> checker) {
        AssertExtensions.assertThrows("Seal txn to abort it failure",
                () -> store.sealTransaction(scope, stream, txnId, false, Optional.empty(), context, executor),
                checker);

        AssertExtensions.assertThrows("Abort txn failure",
                () -> store.abortTransaction(scope, stream, txnId, context, executor),
                checker);
    }
}
