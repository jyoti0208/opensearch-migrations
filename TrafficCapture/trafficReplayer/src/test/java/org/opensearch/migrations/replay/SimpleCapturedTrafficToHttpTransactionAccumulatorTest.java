package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.vavr.Tuple2;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.replay.datatypes.RawPackets;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamWithEmbeddedKey;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.InMemoryConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Some things to consider - Reads, Writes, ReadSegments, WriteSegments, EndOfSegment, EndOfMessage
 * In a well-formed stream, these will always occur in the pattern
 *   `(Read | (ReadSegment* EndOfSegment))* EndOfMessage (Write | (WriteSegment* EndOfSegment))*`
 *
 * The pattern may be repeated any number of times and that pattern may be truncated at any point.
 * However, once a stream of observations is truncated, it is a permanent truncation.  No additional
 * observations will be present, making truncations but not excisions possible.
 *
 * That means that there are 7 types of messages (counting EndOfSegment twice since the context will
 * make it imply EndOfReadSegments or EndOfWriteSegments).  Therefore, there are 7 different types of
 * observations that could be at the beginning of a TrafficStream and 7 different types that could be
 * the last observation of a TrafficStream.  That's already 49 possibilities
 * @return
 */
@Slf4j
public class SimpleCapturedTrafficToHttpTransactionAccumulatorTest {

    public static final int MAX_COMMANDS_IN_CONNECTION = 256;

    enum OffloaderCommandType {
        Read,
        EndOfMessage,
        Write,
        Flush
    }

    @AllArgsConstructor
    static class ObservationDirective {
        public final OffloaderCommandType offloaderCommandType;
        public final int size;

        public static ObservationDirective read(int i) {
            return new ObservationDirective(OffloaderCommandType.Read, i);
        }
        public static ObservationDirective eom() {
            return new ObservationDirective(OffloaderCommandType.EndOfMessage, 0);
        }
        public static ObservationDirective write(int i) {
            return new ObservationDirective(OffloaderCommandType.Write, i);
        }
        public static ObservationDirective flush() {
            return new ObservationDirective(OffloaderCommandType.Flush, 0);
        }
    }

    public static InMemoryConnectionCaptureFactory buildSerializerFactory(int bufferSize, Runnable onClosedCallback) {
        return new InMemoryConnectionCaptureFactory("TEST_NODE_ID", bufferSize, onClosedCallback);
    }

    static ByteBuf makeSequentialByteBuf(int offset, int size) {
        var bb = Unpooled.buffer(size);
        for (int i=0; i<size; ++i) {
            //bb.writeByte((i+offset)%255);
            bb.writeByte('A'+offset);
        }
        return bb;
    }

    static TrafficStream[] makeTrafficStreams(int bufferSize, int interactionOffset,
                                             List<ObservationDirective> directives) throws Exception {
        var connectionFactory = buildSerializerFactory(bufferSize, ()->{});
        var offloader = connectionFactory.createOffloader("TEST_CONNECTION_ID");
        for (var directive : directives) {
            serializeEvent(offloader, interactionOffset++, directive);
        }
        offloader.addCloseEvent(Instant.EPOCH);
        offloader.flushCommitAndResetStream(true).get();
        return connectionFactory.getRecordedTrafficStreamsStream().toArray(TrafficStream[]::new);
    }

    private static void serializeEvent(IChannelConnectionCaptureSerializer offloader, int offset,
                                       ObservationDirective directive) throws IOException {
        switch (directive.offloaderCommandType) {
            case Read:
                offloader.addReadEvent(Instant.EPOCH, makeSequentialByteBuf(offset, directive.size));
                return;
            case EndOfMessage:
                offloader.commitEndOfHttpMessageIndicator(Instant.EPOCH);
                return;
            case Write:
                offloader.addWriteEvent(Instant.EPOCH, makeSequentialByteBuf(offset+3, directive.size));
                return;
            case Flush:
                offloader.flushCommitAndResetStream(false);
                return;
            default:
                throw new RuntimeException("Unknown directive type: " + directive.offloaderCommandType);
        }
    }

    static long calculateAggregateSizeOfPacketBytes(RawPackets packetBytes) {
        return packetBytes.stream().mapToInt(bArr->bArr.length).sum();
    }

    public static Arguments[] loadSimpleCombinations() {
        return new Arguments[] {
                Arguments.of("easyTransactionsAreHandledCorrectly",
                        1024*1024, 0,
                        List.of(ObservationDirective.read(1024),
                                ObservationDirective.eom(),
                                ObservationDirective.write(1024)),
                        List.of(1024, 1024)),
                Arguments.of("transactionStartingWithSegmentsAreHandledCorrectly",
                        1024, 0,
                        List.of(ObservationDirective.read(1024),
                                ObservationDirective.eom(),
                                ObservationDirective.write(1024)),
                        List.of(1024, 1024)),
                Arguments.of("skippedTrafficStreamWithNextStartingOnSegmentsAreHandledCorrectly",
                        512, 1,
                        List.of(ObservationDirective.read(MAX_COMMANDS_IN_CONNECTION),
                                ObservationDirective.eom(),
                                ObservationDirective.write(MAX_COMMANDS_IN_CONNECTION),
                                ObservationDirective.read(MAX_COMMANDS_IN_CONNECTION),
                                ObservationDirective.eom(),
                                ObservationDirective.write(MAX_COMMANDS_IN_CONNECTION)),
                        List.of(MAX_COMMANDS_IN_CONNECTION, MAX_COMMANDS_IN_CONNECTION))
        };
    }

    public static Tuple2<int[],int[]> unzipRequestResponseSizes(List<Integer> collatedList) {
        return new Tuple2
                (IntStream.range(0,collatedList.size()).filter(i->(i%2)==0).map(i->collatedList.get(i)).toArray(),
                IntStream.range(0,collatedList.size()).filter(i->(i%2)==1).map(i->collatedList.get(i)).toArray());
    }

    @ParameterizedTest(name="{0}")
    @MethodSource("loadSimpleCombinations")
    void generateAndTest(String testName, int bufferSize, int skipCount,
                         List<ObservationDirective> directives, List<Integer> expectedSizes) throws Exception {
        var trafficStreams = Arrays.stream(makeTrafficStreams(bufferSize, 0, directives))
                .skip(skipCount);
        List<RequestResponsePacketPair> reconstructedTransactions = new ArrayList<>();
        AtomicInteger requestsReceived = new AtomicInteger(0);
        accumulateTrafficStreamsWithNewAccumulator(trafficStreams, reconstructedTransactions, requestsReceived);
        var splitSizes = unzipRequestResponseSizes(expectedSizes);
        assertReconstructedTransactionsMatchExpectations(reconstructedTransactions, splitSizes._1, splitSizes._2);
        Assertions.assertEquals(requestsReceived.get(), reconstructedTransactions.size());
    }

    /**
     * Returns the traffic stream indices whose contents have been fully received.
     * @param trafficStreams
     * @param aggregations
     * @param requestsReceived
     * @return
     */
    static SortedSet<Integer>
    accumulateTrafficStreamsWithNewAccumulator(Stream<TrafficStream> trafficStreams,
                                                           List<RequestResponsePacketPair> aggregations,
                                                           AtomicInteger requestsReceived) {
        var tsIndicesReceived = new TreeSet<Integer>();
        CapturedTrafficToHttpTransactionAccumulator trafficAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(Duration.ofSeconds(30), null,
                        (id,request) -> requestsReceived.incrementAndGet(),
                        fullPair -> {
                            var sourceIdx = fullPair.requestKey.getSourceRequestIndex();
                            if (fullPair.completionStatus ==
                                    RequestResponsePacketPair.ReconstructionStatus.ClosedPrematurely) {
                                return;
                            }
                            fullPair.getTrafficStreamsHeld().stream()
                                    .forEach(tsk -> tsIndicesReceived.add(tsk.getTrafficStreamIndex()));
                            if (aggregations.size() > sourceIdx) {
                                var oldVal = aggregations.set(sourceIdx, fullPair);
                                if (oldVal != null) {
                                    Assertions.assertEquals(oldVal, fullPair);
                                }
                            } else{
                                aggregations.add(fullPair);
                            }
                        },
                        (rk,timestamp) -> {}
                );
        var tsList = trafficStreams.collect(Collectors.toList());
        trafficStreams = tsList.stream();
        trafficStreams.forEach(ts->trafficAccumulator.accept(new TrafficStreamWithEmbeddedKey(ts)));
        trafficAccumulator.close();
        return tsIndicesReceived;
    }

    static void assertReconstructedTransactionsMatchExpectations(List<RequestResponsePacketPair> reconstructedTransactions,
                                                                 int[] expectedRequestSizes,
                                                                 int[] expectedResponseSizes) {
        log.error("reconstructedTransactions="+ reconstructedTransactions);
        Assertions.assertEquals(expectedRequestSizes.length, reconstructedTransactions.size());
        for (int i = 0; i< reconstructedTransactions.size(); ++i) {
            Assertions.assertEquals((long) expectedRequestSizes[i],
                    calculateAggregateSizeOfPacketBytes(reconstructedTransactions.get(i).requestData.packetBytes));
            Assertions.assertEquals((long) expectedResponseSizes[i],
                    calculateAggregateSizeOfPacketBytes(reconstructedTransactions.get(i).responseData.packetBytes));
        }
    }
}