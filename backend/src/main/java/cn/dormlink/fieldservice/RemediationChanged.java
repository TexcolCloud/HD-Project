package cn.dormlink.fieldservice;

/** A follow-up fact delivered synchronously with the authorized command. */
public record RemediationChanged(
        String id, String buildingId, String studentId, int version, String action) {}
