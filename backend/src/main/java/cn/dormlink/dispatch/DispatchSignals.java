package cn.dormlink.dispatch;

import cn.dormlink.shared.jdbc.Db;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

record DispatchChanged(String orderId, String actorId, String reason) {}

@Component
public class DispatchSignals {
    private final Db db;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate write;
    private final JsonMapper json;
    private final AtomicReference<DispatchChanged> pending = new AtomicReference<>();
    private final AtomicBoolean flushing = new AtomicBoolean();

    DispatchSignals(
            Db db,
            ApplicationEventPublisher events,
            PlatformTransactionManager tx,
            JsonMapper json) {
        this.db = db;
        this.events = events;
        this.json = json;
        write = new TransactionTemplate(tx);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void changed(String order, String actor, String reason) {
        var event = new DispatchChanged(order, actor, reason);
        Runnable persist =
                () -> {
                    db.insert(
                            "INSERT INTO"
                                + " dispatch_event(order_id,actor_user_id,event_type,detail_json,created_at)"
                                + " VALUES(?,?,?,?,?)",
                            order,
                            actor,
                            reason,
                            json.writeValueAsString(Map.of("reason", reason)),
                            Timestamp.from(Instant.now()));
                    events.publishEvent(event);
                };
        // Important facts are durable with their business write; only the recalculation hint is
        // coalesced.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            persist.run();
        } else {
            write.executeWithoutResult(status -> persist.run());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void committed(DispatchChanged event) {
        // No second database connection while the committing request still owns its transaction
        // resource.
        pending.set(event);
    }

    public boolean pending() {
        return pending.get() != null || flushing.get();
    }

    public void flush() {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        DispatchChanged latest = pending.getAndSet(null);
        try {
            if (latest == null) {
                return;
            }
            write.executeWithoutResult(
                    s -> {
                        ensureRevision();
                        db.update(
                                "UPDATE dispatch_revision SET"
                                    + " data_revision=data_revision+1,dirty=TRUE,reason=?,updated_at=?"
                                    + " WHERE id=1",
                                latest.reason(),
                                Timestamp.from(Instant.now()));
                    });
        } catch (RuntimeException failure) {
            pending.compareAndSet(null, latest);
            throw failure;
        } finally {
            flushing.set(false);
        }
    }

    void ensureRevision() {
        if (db.count("SELECT COUNT(*) FROM dispatch_revision WHERE id=1") == 0) {
            db.update(
                    "INSERT INTO dispatch_revision(id,updated_at) VALUES(1,?)",
                    Timestamp.from(Instant.now()));
        }
    }
}
