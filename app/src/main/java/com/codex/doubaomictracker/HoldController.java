package com.codex.doubaomictracker;

/** One transaction at a time; stale Android callbacks cannot affect newer strokes. */
final class HoldController {
    enum State { IDLE, HOLDING, RELEASING, VERIFYING, FAILED }
    interface Driver {
        void dispatch(long token, boolean first, boolean finish);
        void changed(String event);
    }
    private final Driver driver;
    State state = State.IDLE;
    boolean wanted;
    private long serial;
    private long inFlight;
    private long deadline;
    private long heldAt;
    private boolean finishing;
    private boolean recoveryAttempted;
    private boolean pauseAfterRelease;
    private long retryAt;

    HoldController(Driver driver) { this.driver = driver; }
    void update(long now, boolean desire, boolean canStart, boolean canContinue, boolean uiReady) {
        wanted = desire;
        if (state == State.FAILED) return;
        if (state == State.HOLDING && (!desire || !canContinue || now - heldAt >= 30000)) {
            state = State.RELEASING;
            driver.changed("正在松手");
            if (inFlight == 0) send(now, false, true);
        }
        if (state == State.VERIFYING) {
            if (uiReady) {
                if (pauseAfterRelease) fail("手势被中断，已结束并暂停");
                else {
                    state = State.IDLE;
                    retryAt = now + 100;
                    driver.changed("录音界面已结束");
                }
            } else if (now >= deadline) fail("无法确认松手，请手动结束豆包录音");
        }
        if (inFlight != 0 && now >= deadline) {
            inFlight = 0;
            if (finishing && !recoveryAttempted) {
                // An ending stroke cannot be continued again; inspect its actual outcome.
                state = State.VERIFYING;
                deadline = now + 1500;
                driver.changed("松手回调超时，检查录音界面");
            } else if (!recoveryAttempted) {
                recoveryAttempted = true;
                pauseAfterRelease = true;
                state = State.RELEASING;
                driver.changed("手势超时，结束原按压");
                send(now, false, true);
            } else fail("系统未响应松手，请手动结束豆包录音");
        }
        if (state == State.IDLE && desire && canStart && now >= retryAt) {
            state = State.HOLDING;
            heldAt = now;
            recoveryAttempted = false;
            pauseAfterRelease = false;
            driver.changed("开始按压");
            send(now, true, false);
        }
    }
    void result(long token, boolean success, long now) {
        if (token != inFlight || inFlight == 0 || state == State.FAILED) return;
        inFlight = 0;
        if (!success) {
            pauseAfterRelease = true;
            if (!finishing) {
                // A failed callback does not prove the pointer was lifted on every device.
                state = State.RELEASING;
                driver.changed("按压中断，尝试结束原按压");
                send(now, false, true);
            } else {
                state = State.VERIFYING;
                deadline = now + 1500;
                driver.changed("松手未确认，检查录音状态");
            }
        } else if (finishing) {
            state = State.VERIFYING;
            deadline = now + 1500;
            driver.changed("松手指令完成，确认界面");
        } else if (state == State.RELEASING || !wanted) {
            send(now, false, state == State.RELEASING || !wanted);
        }
        // A completed opening stroke with willContinue=true keeps the pointer DOWN.
        // Do not dispatch stationary keepalives: Android rejects eventless continuations.
    }
    void rejected(long token, long now, boolean first) {
        if (token != inFlight) return;
        if (first) {
            inFlight = 0;
            fail("系统未接受按压，已暂停");
        } else result(token, false, now);
    }
    void reset() {
        ++serial;
        inFlight = 0;
        pauseAfterRelease = false;
        state = State.IDLE;
        wanted = false;
        retryAt = 0;
    }
    boolean busy() { return state != State.IDLE && state != State.FAILED; }
    private void send(long now, boolean first, boolean finish) {
        finishing = finish;
        if (finish) state = State.RELEASING;
        inFlight = ++serial;
        deadline = now + 800;
        driver.dispatch(inFlight, first, finish);
    }
    private void fail(String reason) {
        state = State.FAILED;
        inFlight = 0;
        ++serial;
        driver.changed(reason);
    }
}
