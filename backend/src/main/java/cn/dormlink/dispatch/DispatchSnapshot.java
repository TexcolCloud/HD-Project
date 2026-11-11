package cn.dormlink.dispatch;

import cn.dormlink.dispatch.RoutePlanner;
import java.util.*;

public record DispatchSnapshot(
    long revision,
    long dataRevision,
    String fingerprint,
    RoutePlanner.Snapshot input,
    List<Map<String, Object>> managers,
    List<Map<String, Object>> orders,
    List<Map<String, Object>> excluded) {}

