package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
class UtilsTest {
    @Test
    public void testFoldLeft() {
        var groundTruth =
                IntStream.range('A','F')
                        .mapToObj(c->(char)c+"")
                        .collect(Collectors.joining());

        var foldedValue =
                IntStream.range('A','F').mapToObj(c->(char)c+"").collect(Utils.foldLeft("", (a,b) -> a+b));

        log.info("stream concatenated value: " + foldedValue);
        Assertions.assertEquals(groundTruth, foldedValue);

    }
}