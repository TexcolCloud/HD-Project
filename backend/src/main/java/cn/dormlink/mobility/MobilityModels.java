package cn.dormlink.mobility;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/** Shared HTTP shapes; field names and nullability follow OpenAPI 1.2. */
public final class MobilityModels {
    private MobilityModels() {}

    public record Session(
            String id,
            String userId,
            String state,
            int sourceEpoch,
            Instant leaseExpiresAt,
            Instant startedAt,
            Instant lastSampleAt,
            boolean backgroundRequested,
            int version) {}

    public record Context(
            String userId,
            String sessionId,
            String status,
            String source,
            String buildingId,
            String buildingName,
            Integer floorNo,
            Double latitude,
            Double longitude,
            Double accuracyMeters,
            Double speedMps,
            String motion,
            String quality,
            Instant observedAt,
            Instant expiresAt,
            int contextVersion,
            String explanation) {}

    public record Anchor(
            String id,
            String buildingId,
            String buildingName,
            Integer floorNo,
            String name,
            String scanCode,
            Double latitude,
            Double longitude,
            Double radiusMeters,
            boolean enabled,
            int version) {}

    public record Anchors(List<Anchor> items) {}

    public record Policy(
            String consentVersion,
            int maxBatchSize,
            int maxBodyBytes,
            int reportIntervalSeconds,
            int contextTtlSeconds,
            int leaseSeconds) {}

    public static final Policy POLICY = new Policy("mobility-v1", 20, 16384, 15, 60, 120);

    public record View(Session session, Context context, List<Anchor> anchors, Policy policy) {}

    public record Start(
            String clientInstanceId,
            String consentVersion,
            Boolean backgroundRequested,
            Boolean takeover,
            Integer expectedVersion) {}

    public record Action(
            @JsonProperty(required = true) int sourceEpoch,
            @JsonProperty(required = true) int expectedVersion) {}

    public record Sample(
            @JsonProperty(required = true) long sequence,
            @JsonProperty(required = true) String streamId,
            @JsonProperty(required = true) Instant observedAt,
            @JsonProperty(required = true) double elapsedMs,
            Double latitude,
            Double longitude,
            Double accuracyMeters,
            Double speedMps,
            Double speedAccuracyMps,
            Integer stepDelta,
            @JsonProperty(required = true) String motion,
            String clientQuality) {}

    public record Batch(int sourceEpoch, String batchId, List<Sample> samples) {}

    public record Ack(
            String batchId,
            int acceptedCount,
            int rejectedCount,
            long lastSequence,
            Context context) {}

    public record Confirmation(
            @JsonProperty(required = true) int sourceEpoch,
            String anchorId,
            String source,
            @JsonProperty(required = true) int expectedContextVersion) {}

    public record AnchorWrite(
            String buildingId,
            String name,
            Integer floorNo,
            Double latitude,
            Double longitude,
            Double radiusMeters,
            Boolean enabled,
            Integer expectedVersion) {}

    public record TravelModel(
            String fromBuildingId,
            String toBuildingId,
            int minutes,
            int sampleCount,
            int sessionCount,
            boolean enabled,
            int version,
            Instant updatedAt) {}

    public record TravelModels(List<TravelModel> items) {}

    public record Rollback(@JsonProperty(required = true) int expectedVersion) {}

    public record PlannerContext(
            String buildingId,
            Integer floorNo,
            Instant observedAt,
            String quality,
            String explanation) {}
}
