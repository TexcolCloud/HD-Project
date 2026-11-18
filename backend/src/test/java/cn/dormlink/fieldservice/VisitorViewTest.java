package cn.dormlink.fieldservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

class VisitorViewTest {
    @Test
    void overdueUsesElapsedTimeAndOnlyAppliesToVisitsStillOpen() {
        Instant now = Instant.parse("2026-11-19T16:30:00Z");
        Instant cutoff = now.minus(Duration.ofHours(24));

        assertThat(viewedVisit(cutoff.minusMillis(1), null, now)).containsEntry("overdue", true);
        assertThat(viewedVisit(cutoff, null, now)).containsEntry("overdue", false);
        assertThat(viewedVisit(cutoff.plusMillis(1), null, now)).containsEntry("overdue", false);
        assertThat(viewedVisit(now.minus(Duration.ofHours(1)), null, now))
                .containsEntry("overdue", false);
        assertThat(viewedVisit(cutoff.minusMillis(1), now, now))
                .containsEntry("status", "LEFT")
                .containsEntry("overdue", false);
    }

    private Map<String, Object> viewedVisit(Instant arrival, Instant leave, Instant now) {
        var row = new HashMap<String, Object>();
        row.put("phone", "13800000000");
        row.put("arrivedAt", arrival.toString());
        row.put("leftAt", leave == null ? null : leave.toString());
        return OperationsService.visitorView(row, now);
    }
}
