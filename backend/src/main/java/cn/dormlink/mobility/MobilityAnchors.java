package cn.dormlink.mobility;

import static cn.dormlink.mobility.MobilityModels.*;

import cn.dormlink.identity.Actor;
import cn.dormlink.shared.api.ApiException;

import java.util.UUID;

/** Validates the managed place catalog; a scan code never grants access to a place. */
final class MobilityAnchors {
    private final MobilityRepository repository;

    MobilityAnchors(MobilityRepository repository) {
        this.repository = repository;
    }

    Anchors list(Actor actor) {
        return new Anchors(
                "STUDENT".equals(actor.role())
                        ? repository.studentAnchors(actor.id())
                        : repository.anchors(actor.id()));
    }

    void validate(AnchorWrite request) {
        if (request == null
                || request.name() == null
                || request.name().isBlank()
                || request.name().length() > 80
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
    }

    Anchor write(Actor actor, String anchorId, AnchorWrite request) {
        Integer floors = repository.managedFloorCount(actor.id(), request.buildingId());
        if (floors == null) {
            throw ApiException.missing();
        }
        if (request.floorNo() != null && (request.floorNo() < 1 || request.floorNo() > floors)) {
            throw ApiException.bad("楼层不属于该楼栋");
        }
        String savedId = anchorId == null ? UUID.randomUUID().toString() : anchorId;
        if (anchorId == null) {
            repository.createAnchor(savedId, UUID.randomUUID().toString(), request);
        } else {
            Anchor previous = repository.anchor(anchorId, actor.id());
            if (previous == null) {
                throw ApiException.missing();
            }
            if (request.expectedVersion() == null
                    || previous.version() != request.expectedVersion()) {
                throw ApiException.conflict("锚点版本已变化");
            }
            boolean enabled = request.enabled() == null ? previous.enabled() : request.enabled();
            if (!repository.updateAnchor(anchorId, request, enabled)) {
                throw ApiException.conflict("锚点版本已变化");
            }
            repository.invalidateAnchorContexts(anchorId);
        }
        return repository.anchor(savedId, actor.id());
    }
}
