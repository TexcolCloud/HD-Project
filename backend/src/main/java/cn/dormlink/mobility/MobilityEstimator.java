package cn.dormlink.mobility;

import cn.dormlink.mobility.MobilityModels.Sample;

import java.time.Duration;
import java.time.Instant;

/** Pure replayable trust-boundary checks. Client quality and claimed motion are not authority. */
public final class MobilityEstimator {
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private MobilityEstimator() {}

    public record Estimate(
            boolean accepted, Double speedMps, String motion, String quality, String explanation) {}

    public static Estimate estimate(Instant now, Sample previous, Sample sample) {
        if (sample == null
                || sample.observedAt() == null
                || sample.sequence() < 1
                || sample.streamId() == null
                || sample.streamId().isBlank()
                || sample.streamId().length() > 80
                || !Double.isFinite(sample.elapsedMs())
                || sample.elapsedMs() < 0
                || sample.observedAt().isAfter(now.plusSeconds(30))
                || sample.observedAt().isBefore(now.minusSeconds(120))
                || sample.sequence() > 9_007_199_254_740_991L
                || sample.elapsedMs() > 9_007_199_254_740_991L
                || sample.motion() == null
                || !java.util.Set.of("STILL", "WALKING", "UNKNOWN").contains(sample.motion())
                || sample.clientQuality() != null
                        && !java.util.Set.of("LOW", "MEDIUM", "HIGH")
                                .contains(sample.clientQuality())
                || (sample.stepDelta() != null
                        && (sample.stepDelta() < 0 || sample.stepDelta() > 300))
                || !validCoordinates(
                        sample.latitude(), sample.longitude(), sample.accuracyMeters())) {
            return new Estimate(false, null, "UNKNOWN", "LOW", "观测过期、越界或格式无效");
        }
        Double speed = null;
        boolean fresh = !sample.observedAt().isBefore(now.minusSeconds(60));
        boolean precise = sample.accuracyMeters() != null && sample.accuracyMeters() <= 30;
        if (previous != null
                && sample.streamId().equals(previous.streamId())
                && previous.latitude() != null
                && sample.latitude() != null
                && previous.accuracyMeters() != null
                && previous.accuracyMeters() <= 30
                && precise) {
            double seconds = (sample.elapsedMs() - previous.elapsedMs()) / 1000;
            long wallSeconds =
                    Duration.between(previous.observedAt(), sample.observedAt()).getSeconds();
            if (seconds >= 3
                    && seconds <= 60
                    && Math.abs(seconds - wallSeconds) <= 5
                    && distanceMeters(
                                    previous.latitude(),
                                    previous.longitude(),
                                    sample.latitude(),
                                    sample.longitude())
                            > previous.accuracyMeters() + sample.accuracyMeters() + 3.5 * seconds) {
                return new Estimate(false, null, "UNKNOWN", "LOW", "位移与步行时序矛盾，请重新确认位置");
            }
        }
        if (fresh
                && precise
                && finite(sample.speedMps())
                && sample.speedMps() >= 0
                && sample.speedMps() <= 3.5
                && finite(sample.speedAccuracyMps())
                && sample.speedAccuracyMps() >= 0
                && sample.speedAccuracyMps() <= 0.8) {
            speed = sample.speedMps();
        }
        if (speed == null
                && previous != null
                && sample.streamId().equals(previous.streamId())
                && previous.latitude() != null
                && sample.latitude() != null
                && previous.accuracyMeters() != null
                && precise) {
            double seconds = (sample.elapsedMs() - previous.elapsedMs()) / 1000;
            long wallSeconds =
                    Duration.between(previous.observedAt(), sample.observedAt()).getSeconds();
            if (seconds >= 3 && seconds <= 60 && Math.abs(seconds - wallSeconds) <= 5) {
                double distance =
                        distanceMeters(
                                previous.latitude(),
                                previous.longitude(),
                                sample.latitude(),
                                sample.longitude());
                if (distance > previous.accuracyMeters() + sample.accuracyMeters()
                        && distance / seconds <= 3.5) {
                    speed = distance / seconds;
                }
            }
        }
        String motion = speed == null ? "UNKNOWN" : speed < 0.3 ? "STILL" : "WALKING";
        return new Estimate(
                true,
                speed,
                motion,
                speed == null || !fresh ? "LOW" : "MEDIUM",
                speed == null ? "速度依据不足，保留通行基线" : "服务端校验的步行观测；不证明楼层或到场");
    }

    public static boolean validCoordinates(Double latitude, Double longitude, Double accuracy) {
        if (latitude == null && longitude == null && accuracy == null) {
            return true;
        }
        return finite(latitude)
                && latitude >= -90
                && latitude <= 90
                && finite(longitude)
                && longitude >= -180
                && longitude <= 180
                && finite(accuracy)
                && accuracy > 0
                && accuracy <= 10_000;
    }

    static boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double lat = Math.toRadians(lat2 - lat1);
        double lng = Math.toRadians(lng2 - lng1);
        double value =
                Math.sin(lat / 2) * Math.sin(lat / 2)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.sin(lng / 2)
                                * Math.sin(lng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.asin(Math.sqrt(Math.min(1, value)));
    }
}
