package me.cortex.voxy.client.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientLodStreamTest {
    @Test
    void keepsExistingDetailWhileANewSiblingIsDownloading() {
        byte existing = 0b0000_0101;
        byte advertised = (byte) 0b1000_0101;

        assertEquals(existing, ClientLodStream.selectDrawableChildren(existing, advertised, existing));
        assertEquals(advertised, ClientLodStream.selectDrawableChildren(existing, advertised, advertised));
    }

    @Test
    void waitsForCompleteCoverageOnFirstLoad() {
        assertEquals((byte) 0, ClientLodStream.selectDrawableChildren((byte) 0, (byte) 0b111, (byte) 0b101));
        assertEquals((byte) 0b111, ClientLodStream.selectDrawableChildren((byte) 0, (byte) 0b111, (byte) 0b111));
    }

    @Test
    void dropsRemovedAndEvictedChildrenWithoutLosingTheirSiblings() {
        assertEquals((byte) 0b0010,
                ClientLodStream.selectDrawableChildren((byte) 0b0111, (byte) 0b1110, (byte) 0b0011));
        assertEquals((byte) 0, ClientLodStream.selectDrawableChildren((byte) 0b0111, (byte) 0, (byte) 0b0111));
    }
}
