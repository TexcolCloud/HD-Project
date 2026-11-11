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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Device observations are advisory and never execute work-order actions. */
@Service
public class MobilityService {
    private final MobilityRepository repository;
    private final TransactionTemplate transaction;
    private final TransactionTemplate revocationTransaction;
    private final JsonMapper json;
    private final Clock clock;
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
        this.json = json;
        this.clock = clock;
        this.travel = new MobilityTravelLedger(repository, clock);
    }

    public View view(Actor actor) {
        authorize(actor, false);
        return transaction.execute(
                status -> {
                    Work current = repository.current(actor.id(), true);
                    if (current != null && !valid(current, clock.instant())) {
                        invalidate(current, "EXPIRED");
                    }
                    return assemble(actor);
                });
    }

    public View start(Actor actor, Start request) {
        authorize(actor, true);
        if (request == null
                || !POLICY.consentVersion().equals(request.consentVersion())
                || !text(request.clientInstanceId(), 80)) {
            throw ApiException.bad("请确认现场辅助用途，并提供有效的临时来源标识");
        }
        try {
            return transaction.execute(
                    status -> {
                        Instant now = clock.instant();
                        Work current = repository.current(actor.id(), true);
                        Work latest = repository.latest(actor.id());
                        int epoch = latest == null ? 1 : latest.view().sourceEpoch() + 1;
                        if (current != null && !valid(current, now)) {
                            invalidate(current, "EXPIRED");
                            current = null;
                        }
                        if (current != null) {
                            if (!Boolean.TRUE.equals(request.takeover())) {
                                if (actor.sessionId().equals(current.authSessionId())
                                        && request.clientInstanceId()
                                                .equals(current.clientInstanceId())
                                        && "ACTIVE".equals(current.view().state())) {
                                    return assemble(actor);
                                }
                                throw conflict("SOURCE_IN_USE", "已有设备工作会话，请明确接管后开启");
                            }
                            if (request.expectedVersion() == null
                                    || request.expectedVersion() != current.view().version()) {
                                throw ApiException.conflict("工作会话已变化，请刷新后重试");
                            }
                            invalidate(current, "ENDED");
                        }
                        String workId = UUID.randomUUID().toString();
                        repository.update(
                                "INSERT INTO"
                                    + " field_work_session(id,user_id,active_user_id,auth_session_id,client_instance_id,source_epoch,state,consent_version,background_requested,lease_expires_at,started_at)"
                                    + " VALUES(?,?,?,?,?,?,'ACTIVE',?,?,?,?)",
                                workId,
                                id(actor.id()),
                                id(actor.id()),
                                id(actor.sessionId()),
                                request.clientInstanceId(),
                                epoch,
                                request.consentVersion(),
                                Boolean.TRUE.equals(request.backgroundRequested()),
                                ts(now.plusSeconds(120)),
                                ts(now));
                        if (repository.position(actor.id()) == null) {
                            repository.update(
                                    "INSERT INTO"
                                        + " manager_context_snapshot(user_id,session_id,source,explanation)"
                                        + " VALUES(?,?,'NONE',?)",
                                    id(actor.id()),
                                    workId,
                                    "尚无有效观测，请确认现场锚点");
                        } else {
                            repository.update(
                                    "UPDATE manager_context_snapshot SET"
                                        + " session_id=?,context_version=context_version+1 WHERE"
                                        + " user_id=?",
                                    workId,
                                    id(actor.id()));
                        }
                        return assemble(actor);
                    });
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("另一设备已开启工作会话，请刷新并明确接管");
        }
    }

    public View action(Actor actor, String workId, String action, Action request) {
        authorize(actor, "resume".equals(action));
        return transaction.execute(
                status -> {
                    Work work = owned(actor, workId, true);
                    if (work.view().sourceEpoch() != request.sourceEpoch()
                            || work.view().version() != request.expectedVersion()) {
                        throw ApiException.conflict("工作会话来源或版本已变化");
                    }
                    if (!Set.of("ACTIVE", "PAUSED").contains(work.view().state())) {
                        throw ApiException.conflict("工作会话已结束");
                    }
                    if ("end".equals(action)) {
                        invalidate(work, "ENDED");
                    } else if ("pause".equals(action) && "ACTIVE".equals(work.view().state())) {
                        repository.update(
                                "UPDATE field_work_session SET state='PAUSED',version=version+1"
                                        + " WHERE id=?",
                                workId);
                        clearContext(actor.id(), workId);
                    } else if ("resume".equals(action) && "PAUSED".equals(work.view().state())) {
                        if (!valid(work, clock.instant())
                                || !actor.sessionId().equals(work.authSessionId())) {
                            throw ApiException.conflict("来源租约已过期，请重新开启现场辅助");
                        }
                        repository.update(
                                "UPDATE field_work_session SET"
                                    + " state='ACTIVE',source_epoch=source_epoch+1,last_sequence=0,stream_id=NULL,last_elapsed_ms=NULL,lease_expires_at=?,version=version+1"
                                    + " WHERE id=?",
                                ts(clock.instant().plusSeconds(120)),
                                workId);
                    } else {
                        throw ApiException.conflict("不支持当前工作会话状态转换");
                    }
                    return assemble(actor);
                });
    }

    public Ack samples(Actor actor, String workId, Batch request, byte[] rawBody) {
        authorize(actor, true);
        if (rawBody == null
                || rawBody.length > POLICY.maxBodyBytes()
                || request == null
                || !text(request.batchId(), 80)
                || request.samples() == null
                || request.samples().isEmpty()
                || request.samples().size() > 20) {
            throw ApiException.bad("摘要批次限1至20条且正文不超过16KB");
        }
        String hash = hash(rawBody);
        return transaction.execute(
                status -> {
                    Work work = source(actor, workId, request.sourceEpoch());
                    var receipts =
                            repository.query(
                                    "SELECT request_hash,ack_json FROM mobility_batch_receipt WHERE"
                                            + " session_id=? AND batch_id=?",
                                    (row, index) ->
                                            new String[] {row.getString(1), row.getString(2)},
                                    workId,
                                    request.batchId());
                    if (!receipts.isEmpty()) {
                        if (!hash.equals(receipts.getFirst()[0])) {
                            throw ApiException.conflict("批次标识已用于不同正文");
                        }
                        return json.readValue(receipts.getFirst()[1], Ack.class);
                    }
                    Instant now = clock.instant();
                    if (work.view().lastSampleAt() != null
                            && work.view().lastSampleAt().isAfter(now.minusSeconds(1))) {
                        throw new ApiException(429, "RATE_LIMITED", "摘要上报过于频繁，请稍后重试");
                    }
                    Position position = repository.position(actor.id());
                    Sample previous = previous(work, position);
                    String stream = work.streamId();
                    Sample latest = null;
                    MobilityEstimator.Estimate estimate = null;
                    long sequence = work.lastSequence();
                    int accepted = 0, rejected = 0;
                    for (Sample sample : request.samples()) {
                        if (sample == null
                                || sample.sequence() > 9_007_199_254_740_991L
                                || sample.sequence() <= sequence) {
                            rejected++;
                            continue;
                        }
                        sequence = sample.sequence();
                        if (stream != null && !stream.equals(sample.streamId())) {
                            throw conflict("SOURCE_RESTART_REQUIRED", "采集进程已重启，请接管并开启新来源代际");
                        }
                        if (stream == null) {
                            stream = sample.streamId();
                        }
                        var checked = MobilityEstimator.estimate(now, previous, sample);
                        if (!checked.accepted()
                                || (previous != null
                                        && (!sample.observedAt().isAfter(previous.observedAt())
                                                || sample.elapsedMs() <= previous.elapsedMs()))) {
                            rejected++;
                            continue;
                        }
                        latest = sample;
                        estimate = checked;
                        previous = sample;
                        accepted++;
                    }
                    if (latest != null) {
                        saveSample(actor, position, latest, estimate);
                        boolean fresh =
                                !latest.observedAt().isBefore(now.minusSeconds(60))
                                        && !latest.observedAt().isAfter(now.plusSeconds(5));
                        repository.update(
                                "UPDATE field_work_session SET"
                                    + " last_sequence=?,stream_id=?,last_elapsed_ms=?,last_sample_at=?,lease_expires_at=?"
                                    + " WHERE id=?",
                                sequence,
                                latest.streamId(),
                                latest.elapsedMs(),
                                ts(now),
                                ts(fresh ? now.plusSeconds(120) : work.view().leaseExpiresAt()),
                                workId);
                    } else {
                        repository.update(
                                "UPDATE field_work_session SET last_sequence=?,last_sample_at=?"
                                        + " WHERE id=?",
                                sequence,
                                ts(now),
                                workId);
                    }
                    Ack ack = new Ack(request.batchId(), accepted, rejected, sequence, null);
                    repository.update(
                            "INSERT INTO"
                                + " mobility_batch_receipt(session_id,batch_id,request_hash,ack_json,created_at)"
                                + " VALUES(?,?,?,?,?)",
                            workId,
                            request.batchId(),
                            hash,
                            json.writeValueAsString(ack),
                            ts(now));
                    return ack;
                });
    }

    public Context confirm(Actor actor, String workId, Confirmation request) {
        authorize(actor, true);
        return transaction.execute(
                status -> {
                    source(actor, workId, request.sourceEpoch());
                    if (request.source() == null
                            || !Set.of("MANUAL", "QR").contains(request.source())
                            || !text(request.anchorId(), 80)) {
                        throw ApiException.bad("锚点来源无效");
                    }
                    Position position = repository.position(actor.id());
                    if (position == null
                            || position.view().contextVersion()
                                    != request.expectedContextVersion()) {
                        throw ApiException.conflict("工作位置已变化，请刷新后确认");
                    }
                    Anchor anchor = repository.anchor(request.anchorId(), actor.id());
                    if (anchor == null || !anchor.enabled()) {
                        throw ApiException.missing();
                    }
                    Instant now = clock.instant();
                    repository.update(
                            "UPDATE manager_context_snapshot SET source=?,building_id=?,floor_no=?,"
                                + "latitude=NULL,longitude=NULL,accuracy_meters=NULL,speed_mps=NULL,motion='UNKNOWN',quality='MEDIUM',"
                                + "observed_at=?,expires_at=?,anchor_id=?,anchor_confirmed_at=?,context_version=context_version+1,explanation=?"
                                + " WHERE user_id=?",
                            request.source(),
                            id(anchor.buildingId()),
                            anchor.floorNo(),
                            ts(now),
                            ts(now.plusSeconds(120)),
                            anchor.id(),
                            ts(now),
                            "已由本人确认锚点；静态二维码不证明物理到场",
                            id(actor.id()));
                    repository.update(
                            "UPDATE field_work_session SET lease_expires_at=? WHERE id=?",
                            ts(now.plusSeconds(120)),
                            workId);
                    return currentContext(actor.id(), now);
                });
    }

    public Anchors anchors(Actor actor) {
        if ("STUDENT".equals(actor.role())) {
            var result =
                    repository.query(
                            "SELECT a.*,b.name AS building_name FROM campus_anchor a JOIN building"
                                + " b ON b.id=a.building_id JOIN residence r ON"
                                + " r.building_id=a.building_id JOIN student s ON s.id=r.student_id"
                                + " WHERE s.user_id=? AND r.checked_out_at IS NULL AND"
                                + " a.enabled=TRUE AND b.enabled=TRUE ORDER BY a.name LIMIT 500",
                            repository::anchorRow,
                            id(actor.id()));
            return new Anchors(result);
        }
        authorize(actor, false);
        return new Anchors(repository.anchors(actor.id()));
    }

    public Anchor writeAnchor(Actor actor, String anchorId, AnchorWrite request) {
        authorize(actor, false);
        if (request == null
                || !text(request.name(), 80)
                || request.buildingId() == null
                || !MobilityEstimator.validCoordinates(
                        request.latitude(),
                        request.longitude(),
                        request.latitude() == null ? null : 1.0)
                || (request.radiusMeters() != null
                        && (!Double.isFinite(request.radiusMeters())
                                || request.radiusMeters() < 5
                                || request.radiusMeters() > 500))) {
            throw ApiException.bad("锚点名称、坐标或范围无效");
        }
        return transaction.execute(
                status -> {
                    var floors =
                            repository.query(
                                    "SELECT b.floor_count FROM building b JOIN manager_building m"
                                            + " ON m.building_id=b.id WHERE b.id=? AND"
                                            + " m.manager_user_id=? AND b.enabled=TRUE",
                                    (row, index) -> row.getInt(1),
                                    id(request.buildingId()),
                                    id(actor.id()));
                    if (floors.isEmpty()) {
                        throw ApiException.missing();
                    }
                    if (request.floorNo() != null
                            && (request.floorNo() < 1 || request.floorNo() > floors.getFirst())) {
                        throw ApiException.bad("楼层不属于该楼栋");
                    }
                    String savedId = anchorId == null ? UUID.randomUUID().toString() : anchorId;
                    if (anchorId == null) {
                        repository.update(
                                "INSERT INTO"
                                    + " campus_anchor(id,building_id,floor_no,name,scan_code,latitude,longitude,radius_meters,enabled)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                                savedId,
                                id(request.buildingId()),
                                request.floorNo(),
                                request.name().trim(),
                                UUID.randomUUID().toString(),
                                request.latitude(),
                                request.longitude(),
                                request.radiusMeters(),
                                request.enabled() == null || request.enabled());
                    } else {
                        Anchor previous = repository.anchor(anchorId, actor.id());
                        if (previous == null) {
                            throw ApiException.missing();
                        }
                        if (request.expectedVersion() == null
                                || previous.version() != request.expectedVersion()) {
                            throw ApiException.conflict("锚点版本已变化");
                        }
                        if (repository.update(
                                        "UPDATE campus_anchor SET"
                                            + " building_id=?,floor_no=?,name=?,latitude=?,longitude=?,radius_meters=?,enabled=?,version=version+1"
                                            + " WHERE id=? AND version=?",
                                        id(request.buildingId()),
                                        request.floorNo(),
                                        request.name().trim(),
                                        request.latitude(),
                                        request.longitude(),
                                        request.radiusMeters(),
                                        request.enabled() == null
                                                ? previous.enabled()
                                                : request.enabled(),
                                        anchorId,
                                        request.expectedVersion())
                                != 1) {
                            throw ApiException.conflict("锚点版本已变化");
                        }
                        repository.update(
                                "UPDATE manager_context_snapshot SET"
                                    + " building_id=NULL,floor_no=NULL,expires_at=NULL,context_version=context_version+1"
                                    + " WHERE anchor_id=?",
                                anchorId);
                    }
                    return repository.anchor(savedId, actor.id());
                });
    }

    public Set<String> activeUserIds(Instant now) {
        return new LinkedHashSet<>(
                repository.query(
                        "SELECT DISTINCT f.user_id FROM field_work_session f JOIN auth_session a ON"
                            + " a.id=f.auth_session_id JOIN sys_user u ON u.id=f.user_id WHERE"
                            + " f.state='ACTIVE' AND f.lease_expires_at>? AND a.revoked_at IS NULL"
                            + " AND a.expires_at>? AND u.enabled=TRUE AND u.role='MANAGER'",
                        (row, index) -> row.getString(1),
                        ts(now),
                        ts(now)));
    }

    /**
     * Includes unknown/expired position barriers so dispatch cannot revive an older manual floor.
     */
    public Map<String, PlannerContext> plannerContexts(Instant now) {
        Map<String, PlannerContext> result = new LinkedHashMap<>();
        repository.query(
                "SELECT c.*,f.state,f.started_at,f.lease_expires_at,a.revoked_at,a.expires_at AS"
                    + " auth_expires_at,u.enabled AS user_enabled,u.role,b.enabled AS"
                    + " building_enabled,m.building_id AS authorized_building FROM"
                    + " manager_context_snapshot c JOIN field_work_session f ON f.id=c.session_id"
                    + " JOIN auth_session a ON a.id=f.auth_session_id JOIN sys_user u ON"
                    + " u.id=f.user_id LEFT JOIN building b ON b.id=c.building_id LEFT JOIN"
                    + " manager_building m ON m.manager_user_id=c.user_id AND"
                    + " m.building_id=c.building_id",
                (row, index) -> {
                    Instant observed = time(row, "observed_at");
                    Instant expires = time(row, "expires_at");
                    Instant anchor = time(row, "anchor_confirmed_at");
                    boolean live =
                            "ACTIVE".equals(row.getString("state"))
                                    && time(row, "lease_expires_at").isAfter(now)
                                    && time(row, "revoked_at") == null
                                    && time(row, "auth_expires_at").isAfter(now)
                                    && row.getBoolean("user_enabled")
                                    && "MANAGER".equals(row.getString("role"));
                    boolean fresh =
                            live
                                    && observed != null
                                    && !observed.isBefore(now.minusSeconds(60))
                                    && !observed.isAfter(now.plusSeconds(5))
                                    && expires != null
                                    && expires.isAfter(now);
                    String building =
                            fresh
                                            && row.getBoolean("building_enabled")
                                            && row.getString("authorized_building") != null
                                    ? row.getString("building_id")
                                    : null;
                    boolean confirmed =
                            anchor != null
                                    && !anchor.isBefore(now.minusSeconds(120))
                                    && Set.of("MANUAL", "QR").contains(row.getString("source"));
                    Integer floor = building != null && confirmed ? integer(row, "floor_no") : null;
                    String userId = row.getString("user_id");
                    result.put(
                            userId,
                            new PlannerContext(
                                    building,
                                    floor,
                                    observed == null ? time(row, "started_at") : observed,
                                    fresh ? row.getString("quality") : "LOW",
                                    building == null
                                            ? "设备位置未知或已失效，请确认当前位置"
                                            : floor == null
                                                    ? "已识别楼栋，楼层未知，请确认锚点"
                                                    : row.getString("explanation")));
                    return userId;
                });
        return Map.copyOf(result);
    }

    public void departure(Actor actor, String orderId) {
        transaction.executeWithoutResult(status -> travel.departure(actor, orderId));
    }

    public void arrival(Actor actor, String orderId) {
        transaction.executeWithoutResult(status -> travel.arrival(actor, orderId));
    }

    public Map<String, Integer> travelMinutes(Map<String, Integer> baseline) {
        return travel.minutes(baseline);
    }

    public TravelModels travelModels(Actor actor) {
        authorize(actor, false);
        return new TravelModels(travel.visibleModels(actor));
    }

    public TravelModel rollbackTravelModel(
            Actor actor, String fromBuildingId, String toBuildingId, int expectedVersion) {
        authorize(actor, false);
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

    private void authorize(Actor actor, boolean mobile) {
        actor.requireManager();
        if (mobile && !"MOBILE".equals(actor.clientType())) {
            throw new ApiException(403, "MOBILE_REQUIRED", "请在鸿蒙移动端开启现场辅助");
        }
        if (actor.sessionId() == null
                || repository
                        .query(
                                "SELECT a.id FROM auth_session a JOIN sys_user u ON u.id=a.user_id"
                                    + " WHERE a.id=? AND a.user_id=? AND a.revoked_at IS NULL AND"
                                    + " a.expires_at>? AND u.enabled=TRUE AND u.role='MANAGER'",
                                (row, index) -> row.getLong(1),
                                id(actor.sessionId()),
                                id(actor.id()),
                                ts(clock.instant()))
                        .isEmpty()) {
            throw new ApiException(401, "UNAUTHORIZED", "登录会话已失效");
        }
    }

    private Work owned(Actor actor, String workId, boolean lock) {
        Work work = repository.work(workId, lock);
        if (work == null || !actor.id().equals(work.view().userId())) {
            throw ApiException.missing();
        }
        return work;
    }

    private Work source(Actor actor, String workId, int epoch) {
        Work work = owned(actor, workId, true);
        if (!"ACTIVE".equals(work.view().state())
                || epoch != work.view().sourceEpoch()
                || !actor.sessionId().equals(work.authSessionId())
                || !valid(work, clock.instant())) {
            throw conflict("SOURCE_EXPIRED", "当前来源已被接管、暂停或过期，请刷新工作会话");
        }
        return work;
    }

    private boolean valid(Work work, Instant now) {
        return work.view().leaseExpiresAt().isAfter(now)
                && !repository
                        .query(
                                "SELECT a.id FROM auth_session a JOIN sys_user u ON u.id=a.user_id"
                                    + " WHERE a.id=? AND a.revoked_at IS NULL AND a.expires_at>?"
                                    + " AND u.enabled=TRUE AND u.role='MANAGER'",
                                (row, index) -> row.getLong(1),
                                id(work.authSessionId()),
                                ts(now))
                        .isEmpty();
    }

    private View assemble(Actor actor) {
        Work work = repository.current(actor.id(), false);
        if (work == null) {
            work = repository.latest(actor.id());
        }
        return new View(
                work == null ? null : work.view(),
                currentContext(actor.id(), clock.instant()),
                repository.anchors(actor.id()),
                POLICY);
    }

    private Context currentContext(String userId, Instant now) {
        Position position = repository.position(userId);
        if (position == null) {
            return null;
        }
        Context c = position.view();
        Work work = repository.work(c.sessionId(), false);
        boolean live = work != null && "ACTIVE".equals(work.view().state()) && valid(work, now);
        boolean usable =
                live
                        && c.observedAt() != null
                        && c.expiresAt() != null
                        && c.expiresAt().isAfter(now)
                        && c.observedAt().isAfter(now.minusSeconds(120));
        boolean fresh = usable && c.observedAt().isAfter(now.minusSeconds(60));
        boolean anchored =
                usable
                        && position.anchorConfirmedAt() != null
                        && position.anchorConfirmedAt().isAfter(now.minusSeconds(120));
        boolean buildingValid = usable && (anchored || "GPS".equals(c.source()));
        if (c.buildingId() != null
                && repository
                        .query(
                                "SELECT building_id FROM manager_building WHERE manager_user_id=?"
                                        + " AND building_id=?",
                                (row, index) -> row.getString(1),
                                id(userId),
                                id(c.buildingId()))
                        .isEmpty()) {
            buildingValid = false;
            anchored = false;
        }
        return new Context(
                c.userId(),
                c.sessionId(),
                fresh ? "VALID" : usable ? "STALE" : "UNKNOWN",
                usable ? c.source() : "NONE",
                buildingValid ? c.buildingId() : null,
                buildingValid ? c.buildingName() : null,
                anchored ? c.floorNo() : null,
                usable ? c.latitude() : null,
                usable ? c.longitude() : null,
                usable ? c.accuracyMeters() : null,
                fresh ? c.speedMps() : null,
                fresh ? c.motion() : "UNKNOWN",
                fresh ? c.quality() : "LOW",
                c.observedAt(),
                c.expiresAt(),
                c.contextVersion(),
                usable ? c.explanation() : "尚无有效工作位置，请确认锚点或开启采集");
    }

    private Sample previous(Work work, Position position) {
        if (position == null
                || work.streamId() == null
                || work.elapsedMs() == null
                || position.view().observedAt() == null) {
            return null;
        }
        Context c = position.view();
        return new Sample(
                work.lastSequence(),
                work.streamId(),
                c.observedAt(),
                work.elapsedMs(),
                c.latitude(),
                c.longitude(),
                c.accuracyMeters(),
                c.speedMps(),
                null,
                null,
                c.motion(),
                c.quality());
    }

    private void saveSample(
            Actor actor, Position position, Sample sample, MobilityEstimator.Estimate estimate) {
        Context c = position.view();
        boolean anchorValid =
                position.anchorConfirmedAt() != null
                        && position.anchorConfirmedAt().isAfter(clock.instant().minusSeconds(120));
        String gps = gpsBuilding(actor, sample);
        Anchor anchor =
                position.anchorId() == null
                        ? null
                        : repository.anchor(position.anchorId(), actor.id());
        if (gps != null && !gps.equals(c.buildingId())) {
            anchorValid = false;
        }
        if (anchor == null || !anchor.enabled()) {
            anchorValid = false;
        }
        if (anchorValid
                && anchor.latitude() != null
                && anchor.radiusMeters() != null
                && sample.latitude() != null
                && sample.accuracyMeters() <= 30
                && MobilityEstimator.distanceMeters(
                                sample.latitude(),
                                sample.longitude(),
                                anchor.latitude(),
                                anchor.longitude())
                        > anchor.radiusMeters() + sample.accuracyMeters()) {
            anchorValid = false;
        }
        String building = anchorValid ? c.buildingId() : gps;
        repository.update(
                "UPDATE manager_context_snapshot SET"
                    + " source=?,building_id=?,floor_no=?,latitude=?,longitude=?,"
                    + "accuracy_meters=?,speed_mps=?,motion=?,quality=?,observed_at=?,expires_at=?,context_version=context_version+1,explanation=?"
                    + " WHERE user_id=?",
                anchorValid ? c.source() : "GPS",
                building == null ? null : id(building),
                anchorValid ? c.floorNo() : null,
                sample.latitude(),
                sample.longitude(),
                sample.accuracyMeters(),
                estimate.speedMps(),
                estimate.motion(),
                estimate.quality(),
                ts(sample.observedAt()),
                ts(sample.observedAt().plusSeconds(120)),
                estimate.explanation(),
                id(actor.id()));
    }

    private String gpsBuilding(Actor actor, Sample sample) {
        if (sample.latitude() == null
                || sample.accuracyMeters() == null
                || sample.accuracyMeters() > 30
                || sample.observedAt().isBefore(clock.instant().minusSeconds(60))) {
            return null;
        }
        Set<String> matches = new HashSet<>();
        for (Anchor candidate : repository.anchors(actor.id())) {
            if (candidate.enabled()
                    && (candidate.floorNo() == null || candidate.floorNo() == 1)
                    && candidate.latitude() != null
                    && candidate.radiusMeters() != null
                    && MobilityEstimator.distanceMeters(
                                            sample.latitude(),
                                            sample.longitude(),
                                            candidate.latitude(),
                                            candidate.longitude())
                                    + sample.accuracyMeters()
                            <= candidate.radiusMeters()) {
                matches.add(candidate.buildingId());
            }
        }
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    private void invalidate(Work work, String state) {
        repository.update(
                "UPDATE field_work_session SET"
                        + " state=?,active_user_id=NULL,ended_at=?,version=version+1 WHERE id=?",
                state,
                ts(clock.instant()),
                work.view().id());
        clearContext(work.view().userId(), work.view().id());
    }

    private void clearContext(String userId, String workId) {
        repository.update(
                "UPDATE manager_context_snapshot SET"
                    + " source='NONE',building_id=NULL,floor_no=NULL,latitude=NULL,"
                    + "longitude=NULL,accuracy_meters=NULL,speed_mps=NULL,motion='UNKNOWN',quality='LOW',expires_at=NULL,anchor_id=NULL,anchor_confirmed_at=NULL,context_version=context_version+1"
                    + " WHERE user_id=? AND session_id=?",
                id(userId),
                workId);
        repository.update(
                "DELETE FROM mobility_pending_leg WHERE user_id=? AND session_id=?",
                id(userId),
                workId);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void sessionsRevoked(SessionsRevoked event) {
        revocationTransaction.executeWithoutResult(
                status -> {
                    List<Work> affected =
                            repository.query(
                                    "SELECT f.* FROM field_work_session f JOIN auth_session a ON"
                                        + " a.id=f.auth_session_id WHERE f.user_id=? AND f.state IN"
                                        + " ('ACTIVE','PAUSED')"
                                            + (event.sessionId() == null
                                                    ? " AND a.revoked_at IS NOT NULL"
                                                    : " AND f.auth_session_id=?"),
                                    (row, index) -> repository.work(row.getString("id"), false),
                                    event.sessionId() == null
                                            ? new Object[] {id(event.userId())}
                                            : new Object[] {
                                                id(event.userId()), id(event.sessionId())
                                            });
                    affected.forEach(work -> invalidate(work, "EXPIRED"));
                });
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        Instant now = clock.instant();
        transaction.executeWithoutResult(
                status -> {
                    List<String> expired =
                            repository.query(
                                    "SELECT f.id FROM field_work_session f JOIN auth_session a ON"
                                        + " a.id=f.auth_session_id JOIN sys_user u ON"
                                        + " u.id=f.user_id WHERE f.state IN ('ACTIVE','PAUSED') AND"
                                        + " (f.lease_expires_at<=? OR a.revoked_at IS NOT NULL OR"
                                        + " a.expires_at<=? OR u.enabled=FALSE OR"
                                        + " u.role<>'MANAGER') LIMIT 500",
                                    (row, index) -> row.getString(1),
                                    ts(now),
                                    ts(now));
                    expired.forEach(
                            workId -> {
                                Work work = repository.work(workId, true);
                                if (work != null
                                        && Set.of("ACTIVE", "PAUSED").contains(work.view().state())
                                        && !valid(work, now)) {
                                    invalidate(work, "EXPIRED");
                                }
                            });
                    repository.update(
                            "UPDATE manager_context_snapshot SET"
                                + " latitude=NULL,longitude=NULL,accuracy_meters=NULL,speed_mps=NULL,building_id=NULL,floor_no=NULL,quality='LOW',motion='UNKNOWN'"
                                + " WHERE expires_at<=? AND (latitude IS NOT NULL OR building_id IS"
                                + " NOT NULL)",
                            ts(now));
                    var receipts =
                            repository.query(
                                    "SELECT session_id,batch_id FROM mobility_batch_receipt WHERE"
                                            + " created_at<? ORDER BY created_at LIMIT 500",
                                    (row, index) ->
                                            new Object[] {row.getString(1), row.getString(2)},
                                    ts(now.minusSeconds(86400)));
                    repository.batchUpdate(
                            "DELETE FROM mobility_batch_receipt WHERE session_id=? AND batch_id=?",
                            receipts);
                    var legs =
                            repository.query(
                                    "SELECT id FROM travel_leg_summary WHERE created_at<? ORDER BY"
                                            + " created_at LIMIT 500",
                                    (row, index) -> new Object[] {row.getString(1)},
                                    ts(now.minusSeconds(30L * 86400)));
                    repository.batchUpdate("DELETE FROM travel_leg_summary WHERE id=?", legs);
                    repository.update(
                            "DELETE FROM mobility_pending_leg WHERE departed_at<?",
                            ts(now.minusSeconds(1800)));
                });
    }

    private static boolean text(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    private static String hash(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
