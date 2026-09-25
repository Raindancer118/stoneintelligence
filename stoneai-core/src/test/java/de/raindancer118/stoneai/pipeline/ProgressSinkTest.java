package de.raindancer118.stoneai.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressSinkTest {

    @Test
    void should_mapZeroToHundred_ontoTheGivenRange() {
        List<Integer> seen = new ArrayList<>();
        ProgressSink outer = (message, percent) -> seen.add(percent);

        ProgressSink scaled = outer.scaled(20, 80);
        scaled.report("start", 0);
        scaled.report("middle", 50);
        scaled.report("end", 100);

        assertThat(seen).containsExactly(20, 50, 80);
    }

    @Test
    void should_clampOutOfRangeInput() {
        List<Integer> seen = new ArrayList<>();
        ProgressSink outer = (message, percent) -> seen.add(percent);

        ProgressSink scaled = outer.scaled(10, 20);
        scaled.report("below", -5);
        scaled.report("above", 150);

        assertThat(seen).containsExactly(10, 20);
    }

    @Test
    void should_doNothing_when_none() {
        assertThat(ProgressSink.NONE).isNotNull();
        ProgressSink.NONE.report("ignored", 50);
    }
}
