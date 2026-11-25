package me.cortex.voxy.server;

import me.cortex.voxy.common.network.LodSectionCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodStreamBudgetTest {
    @Test
    void refillsAtTheConfiguredRate() {
        var budget = new LodStreamBudget(1, 0);
        budget.sent(1024);
        for (int i = 0; i < 19; i++) budget.tick(1);
        assertFalse(budget.canSend(1024));
        budget.tick(1);
        assertTrue(budget.canSend(1024));
        assertFalse(budget.canSend(1025));
    }

    @Test
    void slowStreamsAccumulateEnoughCreditForOneMaximumSection() {
        var budget = new LodStreamBudget(64, 0);
        int section = LodSectionCodec.MAX_COMPRESSED_BYTES + LodStreamBudget.SECTION_OVERHEAD_BYTES;
        assertFalse(budget.canSend(section));
        for (int i = 0; i < 160; i++) budget.tick(64);
        assertTrue(budget.canSend(section));
        assertFalse(budget.canSend(section + 1));
        budget.sent(section);
        assertFalse(budget.canSend(1));
    }
}
