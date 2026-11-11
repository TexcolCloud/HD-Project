package cn.dormlink.dispatch;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Calculates outside a transaction; the repository validates a fresh locked snapshot on publish.
 */
@Component
class DispatchCoordinator {
    final DispatchRepository repository;
    final DispatchSignals signals;
    final RoutePlanner planner = new RoutePlanner();
    final boolean automatic;
    final AtomicBoolean running = new AtomicBoolean();
    volatile boolean ready;
    volatile String eligibility;

    DispatchCoordinator(
            DispatchRepository repository,
            DispatchSignals signals,
            @Value("${app.dispatch.automatic:true}") boolean automatic) {
        this.repository = repository;
        this.signals = signals;
        this.automatic = automatic;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        repository.initialize();
        eligibility = repository.eligibilityFingerprint();
        ready = true;
        signals.changed(null, null, "APPLICATION_READY");
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1500)
    public void tick() {
        if (!ready || !automatic) {
            return;
        }
        try {
            String current = repository.eligibilityFingerprint();
            if (!current.equals(eligibility)) {
                eligibility = current;
                signals.changed(null, null, "ELIGIBILITY_CHANGED");
            }
            signals.flush();
            if (Boolean.TRUE.equals(repository.revision().get("dirty"))) {
                replan();
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(getClass())
                    .warn("Dispatch recalculation deferred: {}", e.getClass().getSimpleName());
        }
    }

    boolean replan() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                signals.flush();
                var snapshot = repository.snapshot(false);
                var result = planner.plan(snapshot.input());
                if (repository.publish(snapshot, result)) {
                    return true;
                }
            }
            return false;
        } finally {
            running.set(false);
        }
    }
}
