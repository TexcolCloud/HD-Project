package cn.dormlink;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

record DispatchChanged(String orderId, String actorId, String reason) {}

@Component
class DispatchSignals {
  private final Db db;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate write;
  private final JsonMapper json;
  private final ConcurrentLinkedDeque<DispatchChanged> pending = new ConcurrentLinkedDeque<>();
  private final AtomicBoolean flushing = new AtomicBoolean();

  DispatchSignals(
      Db db, ApplicationEventPublisher events, PlatformTransactionManager tx, JsonMapper json) {
    this.db = db;
    this.events = events;
    this.json = json;
    write = new TransactionTemplate(tx);
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  void changed(String order, String actor, String reason) {
    events.publishEvent(new DispatchChanged(order, actor, reason));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void committed(DispatchChanged event) {
    // No second database connection while the committing request still owns its transaction
    // resource.
    pending.addLast(event);
  }

  boolean pending() {
    return !pending.isEmpty() || flushing.get();
  }

  void flush() {
    if (!flushing.compareAndSet(false, true)) return;
    var batch = new ArrayList<DispatchChanged>();
    for (int count = 0; count < 1000; count++) {
      var event = pending.pollFirst();
      if (event == null) break;
      batch.add(event);
    }
    try {
      if (batch.isEmpty()) return;
      write.executeWithoutResult(
          s -> {
            ensureRevision();
            db.update(
                "UPDATE dispatch_revision SET"
                    + " data_revision=data_revision+1,dirty=TRUE,reason=?,updated_at=? WHERE id=1",
                batch.getLast().reason(),
                Timestamp.from(Instant.now()));
            for (var event : batch)
              db.insert(
                  "INSERT INTO"
                      + " dispatch_event(order_id,actor_user_id,event_type,detail_json,created_at)"
                      + " VALUES(?,?,?,?,?)",
                  event.orderId(),
                  event.actorId(),
                  event.reason(),
                  json.writeValueAsString(Map.of("reason", event.reason())),
                  Timestamp.from(Instant.now()));
          });
    } catch (RuntimeException failure) {
      for (int i = batch.size() - 1; i >= 0; i--) pending.addFirst(batch.get(i));
      throw failure;
    } finally {
      flushing.set(false);
    }
  }

  void ensureRevision() {
    if (db.count("SELECT COUNT(*) FROM dispatch_revision WHERE id=1") == 0)
      db.update(
          "INSERT INTO dispatch_revision(id,updated_at) VALUES(1,?)",
          Timestamp.from(Instant.now()));
  }
}
