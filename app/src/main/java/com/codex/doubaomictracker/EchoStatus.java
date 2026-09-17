package com.codex.doubaomictracker;

/** Engine state, not a measurement of acoustic cancellation quality. */
final class EchoStatus {
    final boolean requested;
    final boolean available;
    final boolean enabled;
    final boolean controlled;
    final boolean streamConfirmed;
    final String detail;

    EchoStatus(boolean requested, boolean available, boolean enabled,
               boolean controlled, boolean streamConfirmed, String detail) {
        this.requested = requested;
        this.available = available;
        this.enabled = enabled;
        this.controlled = controlled;
        this.streamConfirmed = streamConfirmed;
        this.detail = detail;
    }

    boolean ready() {
        return requested && available && enabled && controlled && streamConfirmed;
    }

    String label() {
        if (!requested) return "回声消除关闭";
        if (!available) return "回声消除不可用";
        if (!enabled || !controlled) return "回声消除未启用";
        if (!streamConfirmed) return "回声消除待系统确认";
        return "回声消除已启用（效果待实测）";
    }

    String diagnostic() {
        return "aec requested=" + requested + " available=" + available + " enabled=" + enabled
                + " control=" + controlled + " streamConfirmed=" + streamConfirmed + " " + detail;
    }
}
