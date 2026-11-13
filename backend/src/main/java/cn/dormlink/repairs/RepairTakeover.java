package cn.dormlink.repairs;

/** Explicit exceptional continuation after dispatch establishes that the owner is unavailable. */
public record RepairTakeover(
        String orderId, int expectedOrderVersion, String previousManagerId, String reason) {}
