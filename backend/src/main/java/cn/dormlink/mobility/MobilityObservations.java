package cn.dormlink.mobility;

import static cn.dormlink.mobility.MobilityModels.*;
import static cn.dormlink.mobility.MobilityRepository.*;

import cn.dormlink.identity.Actor;
import cn.dormlink.shared.api.ApiException;

import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Admits source-fenced observations and projects conservative, expiring positions. */
final class MobilityObservations {
    private final MobilityRepository repository;
    private final MobilityWorkSessions sessions;
    private final JsonMapper json;
    private final Clock clock;

    MobilityObservations(
            MobilityRepository repository,
            MobilityWorkSessions sessions,
            JsonMapper json,
            Clock clock) {
        this.repository = repository;
        this.sessions = sessions;
        this.json = json;
        this.clock = clock;
    }

    String validate(Batch request, byte[] rawBody) {
        if (rawBody == null
                || rawBody.length > POLICY.maxBodyBytes()
                || request == null
                || !text(request.batchId(), 80)
                || request.samples() == null
                || request.samples().isEmpty()
                || request.samples().size() > POLICY.maxBatchSize()) {
            throw ApiException.bad("摘要批次限1至20条且正文不超过16KB");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    Ack accept(Actor actor, String workId, Batch request, String requestHash) {
        Work work = sessions.source(actor, workId, request.sourceEpoch());
        Receipt receipt = repository.receipt(workId, request.batchId());
        if (receipt != null) {
            if (!requestHash.equals(receipt.requestHash())) {
                throw ApiException.conflict("批次标识已用于不同正文");
            }
            return json.readValue(receipt.ackJson(), Ack.class);
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
                throw new ApiException(409, "SOURCE_RESTART_REQUIRED", "采集进程已重启，请接管并开启新来源代际");
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
        Instant leaseExpiresAt = work.view().leaseExpiresAt();
        if (latest != null) {
            saveSample(actor, position, latest, estimate);
            boolean fresh =
                    !latest.observedAt().isBefore(now.minusSeconds(60))
                            && !latest.observedAt().isAfter(now.plusSeconds(5));
            if (fresh) {
                leaseExpiresAt = now.plusSeconds(POLICY.leaseSeconds());
            }
        }
        repository.observationReceived(workId, sequence, latest, now, leaseExpiresAt);
        Ack ack = new Ack(request.batchId(), accepted, rejected, sequence, null);
        repository.saveReceipt(
                workId, request.batchId(), requestHash, json.writeValueAsString(ack), now);
        return ack;
    }

    Context confirm(Actor actor, String workId, Confirmation request) {
        sessions.source(actor, workId, request.sourceEpoch());
        if (request.source() == null
                || !Set.of("MANUAL", "QR").contains(request.source())
                || !text(request.anchorId(), 80)) {
            throw ApiException.bad("锚点来源无效");
        }
        Position position = repository.position(actor.id());
        if (position == null
                || position.view().contextVersion() != request.expectedContextVersion()) {
            throw ApiException.conflict("工作位置已变化，请刷新后确认");
        }
        Anchor anchor = repository.anchor(request.anchorId(), actor.id());
        if (anchor == null || !anchor.enabled()) {
            throw ApiException.missing();
        }
        Instant now = clock.instant();
        repository.confirmPosition(actor.id(), anchor, request.source(), now);
        repository.renewLease(workId, now.plusSeconds(POLICY.leaseSeconds()));
        return currentContext(actor.id(), now);
    }

    /** Includes expired position barriers so an older manual floor cannot silently reappear. */
    Map<String, PlannerContext> plannerContexts(Instant now) {
        Map<String, PlannerContext> result = new LinkedHashMap<>();
        for (PlanningPosition row : repository.planningPositions()) {
            Context c = row.position().view();
            Instant observed = c.observedAt();
            Instant anchor = row.position().anchorConfirmedAt();
            boolean live =
                    row.active()
                            && row.leaseExpiresAt().isAfter(now)
                            && row.validIdentity()
                            && row.authExpiresAt().isAfter(now);
            boolean fresh =
                    live
                            && observed != null
                            && !observed.isBefore(now.minusSeconds(60))
                            && !observed.isAfter(now.plusSeconds(5))
                            && c.expiresAt() != null
                            && c.expiresAt().isAfter(now);
            String building = fresh && row.authorizedBuilding() ? c.buildingId() : null;
            boolean confirmed =
                    anchor != null
                            && !anchor.isBefore(now.minusSeconds(120))
                            && Set.of("MANUAL", "QR").contains(c.source());
            Integer floor = building != null && confirmed ? c.floorNo() : null;
            result.put(
                    c.userId(),
                    new PlannerContext(
                            building,
                            floor,
                            observed == null ? row.startedAt() : observed,
                            fresh ? c.quality() : "LOW",
                            building == null
                                    ? "设备位置未知或已失效，请确认当前位置"
                                    : floor == null ? "已识别楼栋，楼层未知，请确认锚点" : c.explanation()));
        }
        return Map.copyOf(result);
    }

    Context currentContext(String userId, Instant now) {
        Position position = repository.position(userId);
        if (position == null) {
            return null;
        }
        Context c = position.view();
        Work work = repository.work(c.sessionId(), false);
        boolean live =
                work != null && "ACTIVE".equals(work.view().state()) && sessions.valid(work, now);
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
        if (c.buildingId() != null && !repository.hasBuildingScope(userId, c.buildingId())) {
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
        repository.savePosition(
                actor.id(),
                anchorValid ? c.source() : "GPS",
                building,
                anchorValid ? c.floorNo() : null,
                sample,
                estimate);
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

    private static boolean text(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }
}
