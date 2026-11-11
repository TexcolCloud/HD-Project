package cn.dormlink.mobility;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dormlink.mobility.MobilityModels.Sample;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class MobilityEstimatorTest {
    private final Instant now = Instant.parse("2026-11-11T01:00:00Z");

    @Test
    void clientHighQualityCannotTurnGpsDriftIntoWalkingOrAConfidentSpeed() {
        var first = sample(1, now.minusSeconds(10), 0, 30.0, 120.0, 20.0, null);
        var drift = sample(2, now, 10000, 30.00001, 120.00001, 20.0, null);
        var estimate = MobilityEstimator.estimate(now, first, drift);
        assertThat(estimate.accepted()).isTrue();
        assertThat(estimate.speedMps()).isNull();
        assertThat(estimate.quality()).isEqualTo("LOW");
        assertThat(estimate.motion()).isEqualTo("UNKNOWN");
    }

    @Test
    void claimedWalkingSpeedCannotHideAnImpossibleSameStreamPositionJump() {
        var previous = sample(1, now.minusSeconds(10), 0, 30.0, 120.0, 5.0, 1.2);
        var impossible = sample(2, now, 10000, 31.0, 121.0, 5.0, 1.2);
        assertThat(MobilityEstimator.estimate(now, previous, impossible).accepted()).isFalse();
        var unknown = sample(3, now, 10000, null, null, null, 1.2);
        assertThat(MobilityEstimator.estimate(now, previous, unknown).speedMps()).isNull();
    }

    @Test
    void oldFutureAndImpossibleSamplesAreRejectedRatherThanBecomingCurrent() {
        assertThat(
                        MobilityEstimator.estimate(
                                        now,
                                        null,
                                        sample(1, now.minusSeconds(121), 0, 30.0, 120.0, 5.0, 1.2))
                                .accepted())
                .isFalse();
        assertThat(
                        MobilityEstimator.estimate(
                                        now,
                                        null,
                                        sample(1, now.plusSeconds(31), 0, 30.0, 120.0, 5.0, 1.2))
                                .accepted())
                .isFalse();
        assertThat(
                        MobilityEstimator.estimate(
                                        now, null, sample(1, now, 0, 300.0, 120.0, 5.0, 1.2))
                                .accepted())
                .isFalse();
        assertThat(
                        MobilityEstimator.estimate(
                                        now, null, sample(1, now, 0, 30.0, 120.0, 5.0, 20.0))
                                .speedMps())
                .isNull();
    }

    private Sample sample(
            long sequence,
            Instant time,
            double elapsed,
            Double lat,
            Double lng,
            Double accuracy,
            Double speed) {
        return new Sample(
                sequence,
                "stream-1",
                time,
                elapsed,
                lat,
                lng,
                accuracy,
                speed,
                speed == null ? null : 0.2,
                3,
                "WALKING",
                "HIGH");
    }
}
