package com.codex.doubaomictracker;

final class ReferenceStatus {
    final boolean ready;
    final float rawRms;
    final float referenceRms;
    final String message;
    ReferenceStatus(boolean ready, float rawRms, float referenceRms, String message) {
        this.ready = ready;
        this.rawRms = rawRms;
        this.referenceRms = referenceRms;
        this.message = message;
    }
}
