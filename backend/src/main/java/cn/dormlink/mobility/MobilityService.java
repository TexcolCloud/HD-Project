package cn.dormlink.mobility;

import static cn.dormlink.mobility.MobilityModels.*;
import static cn.dormlink.mobility.MobilityRepository.*;

import cn.dormlink.identity.Actor;
import cn.dormlink.identity.SessionsRevoked;
import cn.dormlink.shared.api.ApiException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Transactional entry point; observations are advisory and never execute work-order actions. */
@Service
public class MobilityService {
    private final MobilityRepository repository;
    private final TransactionTemplate transaction;
    private final TransactionTemplate revocationTransaction;
    private final Clock clock;
    private final MobilityWorkSessions sessions;
    private final MobilityObservations observations;
    private final MobilityAnchors anchors;
    private final MobilityTravelLedger travel;

    @Autowired
    public MobilityService(
            MobilityRepository repository, PlatformTransactionManager manager, JsonMapper json) {
        this(repository, manager, json, Clock.systemUTC());
    }

    MobilityService(
            MobilityRepository repository,
            PlatformTransactionManager manager,
            JsonMapper json,
            Clock clock) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
        this.revocationTransaction = new TransactionTemplate(manager);
        this.revocationTransaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.sessions = new MobilityWorkSessions(repository, clock);
        this.observations = new MobilityObservations(repository, sessions, json, clock);
        this.anchors = new MobilityAnchors(repository);
        this.travel = new MobilityTravelLedger(repository, clock);
    }

    public View view(Actor actor) {
        sessions.authorize(actor, false);
        return transaction.execute(
                status -> {
                    sessions.expireCurrent(actor.id());
                    return assemble(actor);
                });
    }

    public View start(Actor actor, Start request) {
        sessions.authorize(actor, true);
        if (request == null
                || !POLICY.consentVersion().equals(request.consentVersion())
                || request.clientInstanceId() == null
                || request.clientInstanceId().isBlank()
                || request.clientInstanceId().length() > 80) {
            throw ApiException.bad("请确认现场辅助用途，并提供有效的临时来源标识");
        }
        try {
            return transaction.execute(
                    status -> {
                        sessions.start(actor, request);
                        return assemble(actor);
                    });
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("另一设备已开启工作会话，请刷新并明确接管");
        }
    }

    public View action(Actor actor, String workId, String action, Action request) {
        sessions.authorize(actor, "resume".equals(action));
        return transaction.execute(
                status -> {
                    sessions.action(actor, workId, action, request);
                    return assemble(actor);
                });
    }

    public Ack samples(Actor actor, String workId, Batch request, byte[] rawBody) {
        sessions.authorize(actor, true);
        String requestHash = observations.validate(request, rawBody);
        return transaction.execute(
                status -> observations.accept(actor, workId, request, requestHash));
    }

    public Context confirm(Actor actor, String workId, Confirmation request) {
        sessions.authorize(actor, true);
        return transaction.execute(status -> observations.confirm(actor, workId, request));
    }

    public Anchors anchors(Actor actor) {
        if (!"STUDENT".equals(actor.role())) {
            sessions.authorize(actor, false);
        }
        return anchors.list(actor);
    }

    public Anchor writeAnchor(Actor actor, String anchorId, AnchorWrite request) {
        sessions.authorize(actor, false);
        anchors.validate(request);
        return transaction.execute(status -> anchors.write(actor, anchorId, request));
    }

    public Set<String> activeUserIds(Instant now) {
        return new LinkedHashSet<>(repository.activeUserIds(now));
    }

    public Map<String, PlannerContext> plannerContexts(Instant now) {
        return observations.plannerContexts(now);
    }

    public void departure(Actor actor, String orderId) {
        transaction.executeWithoutResult(status -> travel.departure(actor, orderId));
    }

    public void arrival(Actor actor, String orderId) {
        transaction.executeWithoutResult(status -> travel.arrival(actor, orderId));
    }

    public void abandonLeg(String userId, String orderId) {
        repository.update(
                "DELETE FROM mobility_pending_leg WHERE user_id=? AND order_id=?",
                id(userId),
                id(orderId));
    }

    public Map<String, Integer> travelMinutes(Map<String, Integer> baseline) {
        return travel.minutes(baseline);
    }

    public Map<String, Map<String, Integer>> personalTravelMinutes(Map<String, Integer> shared) {
        return travel.personalMinutes(shared);
    }

    public Efficiency efficiency(Actor actor) {
        sessions.authorize(actor, false);
        return travel.efficiency(actor);
    }

    public TravelModels travelModels(Actor actor) {
        sessions.authorize(actor, false);
        return new TravelModels(travel.visibleModels(actor));
    }

    public TravelModel rollbackTravelModel(
            Actor actor, String fromBuildingId, String toBuildingId, int expectedVersion) {
        sessions.authorize(actor, false);
        return transaction.execute(
                status -> {
                    travel.rollback(actor, fromBuildingId, toBuildingId, expectedVersion);
                    return travel.visibleModels(actor).stream()
                            .filter(
                                    model ->
                                            model.fromBuildingId().equals(fromBuildingId)
                                                    && model.toBuildingId().equals(toBuildingId))
                            .findFirst()
                            .orElseThrow(ApiException::missing);
                });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void sessionsRevoked(SessionsRevoked event) {
        // AFTER_COMMIT cannot reuse the completed login transaction for these writes.
        revocationTransaction.executeWithoutResult(status -> sessions.revoked(event));
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        transaction.executeWithoutResult(status -> sessions.cleanup());
    }

    private View assemble(Actor actor) {
        Work work = repository.current(actor.id(), false);
        if (work == null) {
            work = repository.latest(actor.id());
        }
        return new View(
                work == null ? null : work.view(),
                observations.currentContext(actor.id(), clock.instant()),
                repository.anchors(actor.id()),
                POLICY);
    }
}
