package cn.dormlink.mobility;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelLearningTest {
    @Test
    void oneSelfReportedSessionCannotChangeTheSharedWalkingBaseline() {
        assertThat(TravelLearning.learn(List.of(new TravelLearning.Leg("one",120),
                new TravelLearning.Leg("one",125),new TravelLearning.Leg("one",123),
                new TravelLearning.Leg("one",121),new TravelLearning.Leg("one",122)))).isEmpty();
    }
    @Test
    void independentConfirmedTripsUseRobustElapsedTimeAndIsolateTheOutlier() {
        var result=TravelLearning.learn(List.of(new TravelLearning.Leg("one",120),
                new TravelLearning.Leg("one",121),new TravelLearning.Leg("two",119),
                new TravelLearning.Leg("two",122),new TravelLearning.Leg("three",120),
                new TravelLearning.Leg("four",1700)));
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().minutes()).isEqualTo(2);
        assertThat(result.orElseThrow().sampleCount()).isEqualTo(5);
        assertThat(result.orElseThrow().sessionCount()).isEqualTo(3);
    }
}
