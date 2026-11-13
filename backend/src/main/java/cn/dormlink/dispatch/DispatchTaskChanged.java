package cn.dormlink.dispatch;

/** Explicit task action, published synchronously with its transaction for dependent projections. */
public record DispatchTaskChanged(
        String orderId, String buildingId, String action, String actorId, int version) {}
