package cn.dormlink.repairs;

import java.util.Locale;

/** Synchronous fact: dependent invariants must succeed in the same transaction as the repair. */
public record RepairChanged(RepairSnapshot order, Action action, String actorId) {
    public enum Action {
        SUBMIT,
        ACCEPT,
        REJECT,
        START,
        REWORK,
        FINISH,
        CONFIRM,
        CANCEL,
        NOTES,
        VISIT,
        HANDOFF,
        TAKEOVER;

        static Action fromWire(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
}
