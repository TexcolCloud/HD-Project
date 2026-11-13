package cn.dormlink.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

class DispatchClockTest {
    @Test
    void aTimeBoundaryNeedsNoExternalChangeAndFailedPersistenceRemainsRetryable() {
        Instant now = Instant.parse("2026-11-12T01:00:00Z");
        var repository = mock(DispatchRepository.class);
        var signals = mock(DispatchSignals.class);
        when(repository.eligibilityFingerprint()).thenReturn("same");
        when(repository.revision()).thenReturn(Map.of("dirty", false));
        var coordinator =
                new DispatchCoordinator(
                        repository, signals, true, Clock.fixed(now, ZoneOffset.UTC));
        coordinator.ready = true;
        coordinator.eligibility = "same";
        coordinator.nextTimeCheck = now.plusSeconds(1);
        coordinator.tick();
        verify(signals, never()).changed(null, null, "TIME_BOUNDARY_REACHED");
        coordinator.nextTimeCheck = now;
        doThrow(new IllegalStateException("temporary database failure"))
                .doNothing()
                .when(signals)
                .changed(null, null, "TIME_BOUNDARY_REACHED");
        coordinator.tick();
        assertThat(coordinator.nextTimeCheck).isEqualTo(now);
        coordinator.tick();
        verify(signals, times(2)).changed(null, null, "TIME_BOUNDARY_REACHED");
        assertThat(coordinator.nextTimeCheck).isNull();
        coordinator.tick();
        verify(signals, times(2)).changed(null, null, "TIME_BOUNDARY_REACHED");
    }
}
