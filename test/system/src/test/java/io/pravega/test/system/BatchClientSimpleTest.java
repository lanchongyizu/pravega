/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.test.system;

import com.google.common.collect.Lists;
import io.pravega.client.ClientConfig;
import io.pravega.client.ClientFactory;
import io.pravega.client.admin.ReaderGroupManager;
import io.pravega.client.admin.StreamManager;
import io.pravega.client.batch.BatchClient;
import io.pravega.client.batch.SegmentRange;
import io.pravega.client.batch.StreamInfo;
import io.pravega.client.netty.impl.ConnectionFactory;
import io.pravega.client.netty.impl.ConnectionFactoryImpl;
import io.pravega.client.stream.Checkpoint;
import io.pravega.client.stream.EventRead;
import io.pravega.client.stream.EventStreamReader;
import io.pravega.client.stream.EventStreamWriter;
import io.pravega.client.stream.EventWriterConfig;
import io.pravega.client.stream.ReaderConfig;
import io.pravega.client.stream.ReaderGroup;
import io.pravega.client.stream.ReaderGroupConfig;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.Stream;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.client.stream.StreamCut;
import io.pravega.client.stream.impl.ClientFactoryImpl;
import io.pravega.client.stream.impl.ControllerImpl;
import io.pravega.client.stream.impl.ControllerImplConfig;
import io.pravega.client.stream.impl.JavaSerializer;
import io.pravega.common.concurrent.ExecutorServiceHelpers;
import io.pravega.common.concurrent.Futures;
import io.pravega.common.hash.RandomFactory;
import io.pravega.test.system.framework.Environment;
import io.pravega.test.system.framework.SystemTestRunner;
import io.pravega.test.system.framework.Utils;
import io.pravega.test.system.framework.services.Service;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import mesosphere.marathon.client.MarathonException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@Slf4j
@RunWith(SystemTestRunner.class)
public class BatchClientSimpleTest {

    private static final String STREAM = "testBatchClientStream";
    private static final String SCOPE = "testBatchClientScope" + RandomFactory.create().nextInt(Integer.MAX_VALUE);
    private static final String READER_GROUP = "testBatchClientRG" + RandomFactory.create().nextInt(Integer.MAX_VALUE);
    private static final int RG_PARALLELISM = 4;
    @Rule
    public Timeout globalTimeout = Timeout.seconds(8 * 60);

    private final ScheduledExecutorService executor = ExecutorServiceHelpers.newScheduledThreadPool(4, "executor");
    private final ScalingPolicy scalingPolicy = ScalingPolicy.fixed(RG_PARALLELISM);
    private final StreamConfiguration config = StreamConfiguration.builder().scope(SCOPE)
                                                                  .streamName(STREAM)
                                                                  .scalingPolicy(scalingPolicy).build();
    private URI controllerURI = null;
    private StreamManager streamManager = null;

    /**
     * This is used to setup the services required by the system test framework.
     *
     * @throws MarathonException When error in setup.
     */
    @Environment
    public static void initialize() throws MarathonException {

        // 1. Check if zk is running, if not start it.
        Service zkService = Utils.createZookeeperService();
        if (!zkService.isRunning()) {
            zkService.start(true);
        }

        List<URI> zkUris = zkService.getServiceDetails();
        log.debug("Zookeeper service details: {}", zkUris);
        // Get the zk ip details and pass it to bk, host, controller.
        URI zkUri = zkUris.get(0);

        // 2. Check if bk is running, otherwise start, get the zk ip.
        Service bkService = Utils.createBookkeeperService(zkUri);
        if (!bkService.isRunning()) {
            bkService.start(true);
        }

        List<URI> bkUris = bkService.getServiceDetails();
        log.debug("Bookkeeper service details: {}", bkUris);

        // 3. Start controller.
        Service conService = Utils.createPravegaControllerService(zkUri);
        if (!conService.isRunning()) {
            conService.start(true);
        }

        List<URI> conUris = conService.getServiceDetails();
        log.debug("Pravega controller service details: {}", conUris);

        // 4.Start segmentstore.
        Service segService = Utils.createPravegaSegmentStoreService(zkUri, conUris.get(0));
        if (!segService.isRunning()) {
            segService.start(true);
        }

        List<URI> segUris = segService.getServiceDetails();
        log.debug("Pravega segmentstore service details: {}", segUris);
    }

    @Before
    public void setup() {
        Service conService = Utils.createPravegaControllerService(null);
        List<URI> ctlURIs = conService.getServiceDetails();
        controllerURI = ctlURIs.get(0);
        streamManager = StreamManager.create(controllerURI);
        assertTrue("Creating scope", streamManager.createScope(SCOPE));
        assertTrue("Creating stream", streamManager.createStream(SCOPE, STREAM, config));
    }

    @After
    public void tearDown() {
        streamManager.close();
        ExecutorServiceHelpers.shutdown(executor);
    }

    /**
     * This test verifies the basic functionality of {@link BatchClient}, including stream metadata checks, segment
     * counts, parallel segment reads and reads with offsets using stream cuts.
     */
    @Test
    public void batchClientSimpleTest() {
        final int totalEvents = RG_PARALLELISM * 100;
        final int offsetEvents = RG_PARALLELISM * 20;
        final int batchIterations = 4;
        final Stream stream = Stream.of(SCOPE, STREAM);
        @Cleanup
        ConnectionFactory connectionFactory = new ConnectionFactoryImpl(ClientConfig.builder().build());
        ControllerImpl controller = new ControllerImpl(ControllerImplConfig.builder()
                                                                           .clientConfig(ClientConfig.builder()
                                                                           .controllerURI(controllerURI).build()).build(),
                                                                            connectionFactory.getInternalExecutor());
        @Cleanup
        ClientFactory clientFactory = new ClientFactoryImpl(SCOPE, controller);
        log.info("Invoking batchClientSimpleTest test with Controller URI: {}", controllerURI);
        @Cleanup
        ReaderGroupManager groupManager = ReaderGroupManager.withScope(SCOPE, controllerURI);
        groupManager.createReaderGroup(READER_GROUP, ReaderGroupConfig.builder().disableAutomaticCheckpoints()
                                                                      .stream(SCOPE + "/" + STREAM).build());
        ReaderGroup readerGroup = groupManager.getReaderGroup(READER_GROUP);

        // Write events to the Stream.
        writeDummyEvents(clientFactory, STREAM, totalEvents);

        // Instantiate readers to consume from Stream up to truncatedEvents.
        List<CompletableFuture<Integer>> futures = readDummyEvents(clientFactory, READER_GROUP, RG_PARALLELISM, offsetEvents);
        Futures.allOf(futures).join();

        // Create a stream cut on the specified offset position.
        Checkpoint cp = readerGroup.initiateCheckpoint("batchClientCheckpoint", executor).join();
        StreamCut streamCut = cp.asImpl().getPositions().values().iterator().next();

        // Instantiate the batch client and assert it provides correct stream info.
        log.debug("Creating batch client.");
        BatchClient batchClient = clientFactory.createBatchClient();
        StreamInfo streamInfo = batchClient.getStreamInfo(stream).join();
        log.debug("Validating stream metadata fields.");
        assertEquals("Expected Stream name: ", STREAM, streamInfo.getStreamName());
        assertEquals("Expected Scope name: ", SCOPE, streamInfo.getScope());

        // Test that we can read events from parallel segments from an offset onwards.
        log.debug("Reading events from stream cut onwards in parallel.");
        List<SegmentRange> ranges = Lists.newArrayList(batchClient.getSegments(stream, streamCut, StreamCut.UNBOUNDED).getIterator());
        assertEquals("Expected events read: ", totalEvents - offsetEvents, readFromRanges(ranges, batchClient));

        // Emulate the behavior of Hadoop client: i) Get tail of Stream, ii) Read from current point until tail, iii) repeat.
        log.debug("Reading in batch iterations.");
        StreamCut currentTailStreamCut = batchClient.getStreamInfo(stream).join().getTailStreamCut();
        int readEvents = 0;
        for (int i = 0; i < batchIterations; i++) {
            writeDummyEvents(clientFactory, STREAM, totalEvents);

            // Read all the existing events in parallel segments from the previous tail to the current one.
            ranges = Lists.newArrayList(batchClient.getSegments(stream, currentTailStreamCut, StreamCut.UNBOUNDED).getIterator());
            assertEquals("Expected number of segments: ", RG_PARALLELISM, ranges.size());
            readEvents += readFromRanges(ranges, batchClient);
            log.debug("Events read in parallel so far: {}.", readEvents);
            currentTailStreamCut = batchClient.getStreamInfo(stream).join().getTailStreamCut();
        }

        assertEquals("Expected events read: .", totalEvents * batchIterations, readEvents);

        // Truncate the stream in first place.
        log.debug("Truncating stream at event {}.", offsetEvents);
        assertTrue(controller.truncateStream(SCOPE, STREAM, streamCut).join());

        // Test the batch client when we select to start reading a Stream from a truncation point.
        StreamCut initialPosition = batchClient.getStreamInfo(stream).join().getHeadStreamCut();
        List<SegmentRange> newRanges = Lists.newArrayList(batchClient.getSegments(stream, initialPosition, StreamCut.UNBOUNDED).getIterator());
        assertEquals("Expected events read: ", (totalEvents - offsetEvents) + totalEvents * batchIterations,
                    readFromRanges(newRanges, batchClient));
        log.debug("Events correctly read from Stream: simple batch client test passed.");
    }

    // Start utils region

    private int readFromRanges(List<SegmentRange> ranges, BatchClient batchClient) {
        List<CompletableFuture<Integer>> eventCounts = ranges
                .parallelStream()
                .map(range -> CompletableFuture.supplyAsync(() -> batchClient.readSegment(range, new JavaSerializer<>()))
                                               .thenApplyAsync(segmentIterator -> {
                                                   log.debug("Thread " + Thread.currentThread().getId() + " reading events.");
                                                   int numEvents = Lists.newArrayList(segmentIterator).size();
                                                   segmentIterator.close();
                                                   return numEvents;
                                               }))
                .collect(Collectors.toList());
        return eventCounts.stream().map(CompletableFuture::join).mapToInt(Integer::intValue).sum();
    }

    private void writeDummyEvents(ClientFactory clientFactory, String streamName, int totalEvents) {
        @Cleanup
        EventStreamWriter<String> writer = clientFactory.createEventWriter(streamName, new JavaSerializer<>(),
                EventWriterConfig.builder().build());
        for (int i = 0; i < totalEvents; i++) {
            writer.writeEvent(String.valueOf(i)).join();
            log.debug("Writing event: {} to stream {}", i, streamName);
        }
    }

    private List<CompletableFuture<Integer>> readDummyEvents(ClientFactory client, String rGroup, int numReaders, int limit) {
        List<EventStreamReader<String>> readers = new ArrayList<>();
        for (int i = 0; i < numReaders; i++) {
            readers.add(client.createReader(String.valueOf(i), rGroup, new JavaSerializer<>(), ReaderConfig.builder().build()));
        }

        return readers.stream().map(r -> CompletableFuture.supplyAsync(() -> readEvents(r, limit / numReaders))).collect(toList());
    }

    @SneakyThrows
    private <T> int readEvents(EventStreamReader<T> reader, int limit) {
        EventRead<T> event;
        int validEvents = 0;
        try {
            do {
                event = reader.readNextEvent(1000);
                if (event.getEvent() != null) {
                    validEvents++;
                }
            } while ((event.getEvent() != null || event.isCheckpoint()) && validEvents < limit);
        } finally {
            reader.close();
        }

        return validEvents;
    }

    // End utils region
}
