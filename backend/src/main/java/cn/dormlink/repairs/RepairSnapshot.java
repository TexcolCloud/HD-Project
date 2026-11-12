package cn.dormlink.repairs;

import cn.dormlink.shared.jdbc.Db;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Authorized repair facts for collaborating modules; not a database row or an API response. */
public record RepairSnapshot(
        String id,
        String orderNo,
        String roomId,
        String buildingId,
        String reporterName,
        String contactName,
        String contactPhone,
        String title,
        String description,
        String category,
        String priority,
        Status status,
        Instant submittedAt,
        String handlerUserId,
        String handlerName,
        int version) {
    public enum Status {
        SUBMITTED,
        ACCEPTED,
        PROCESSING,
        PENDING_CONFIRMATION,
        COMPLETED,
        CANCELLED,
        REJECTED
    }

    public static RepairSnapshot fromRow(Map<String, Object> row) {
        return new RepairSnapshot(
                text(row, "id"),
                text(row, "orderNo"),
                text(row, "roomId"),
                text(row, "buildingId"),
                text(row, "reporterName"),
                text(row, "contactName"),
                text(row, "contactPhone"),
                text(row, "title"),
                text(row, "description"),
                text(row, "category"),
                text(row, "priority"),
                Status.valueOf(text(row, "status")),
                Instant.parse(text(row, "submittedAt")),
                text(row, "handlerUserId"),
                text(row, "handlerName"),
                (int) Db.number(row, "version"));
    }

    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }
}
