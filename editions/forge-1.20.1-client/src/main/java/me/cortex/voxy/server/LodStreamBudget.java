package me.cortex.voxy.server;

import me.cortex.voxy.common.network.LodSectionCodec;

final class LodStreamBudget {
    static final int SECTION_OVERHEAD_BYTES = 40;
    private static final int TICKS_PER_SECOND = 20;
    private static final int MAX_SECTION_BYTES = LodSectionCodec.MAX_COMPRESSED_BYTES + SECTION_OVERHEAD_BYTES;

    // Keep twentieths of a byte so small rates retain the full configured refill.
    private long credit;
    private final int burstExtraBytes;

    LodStreamBudget(int rateKiB, int burstExtraBytes) {
        this.burstExtraBytes = burstExtraBytes;
        this.credit = (rateKiB * 1024L + burstExtraBytes) * TICKS_PER_SECOND;
    }

    void tick(int rateKiB) {
        long bytesPerSecond = rateKiB * 1024L;
        long capacity = Math.max(bytesPerSecond + this.burstExtraBytes, MAX_SECTION_BYTES);
        this.credit = Math.min(capacity * TICKS_PER_SECOND, this.credit + bytesPerSecond);
    }

    boolean canSend(int bytes) {
        return this.credit >= bytes * (long) TICKS_PER_SECOND;
    }

    void sent(int bytes) {
        this.credit -= bytes * (long) TICKS_PER_SECOND;
    }
}
