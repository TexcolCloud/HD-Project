package cn.dormlink.repairs;

/** Task eligibility belongs to dispatch; repair ownership is still version checked here. */
public record RepairTransfer(
        String orderId,
        int expectedOrderVersion,
        String targetManagerId,
        String targetManagerName,
        String reason) {}
