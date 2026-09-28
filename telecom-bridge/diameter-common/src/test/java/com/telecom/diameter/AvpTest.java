package com.telecom.diameter;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class AvpTest {

    @Test
    void utf8AvpIsPaddedToFourByteBoundaryButLengthExcludesPadding() {
        Avp avp = Avp.utf8(264, "abcde"); // 8 header + 5 data = 13, padded to 16
        ByteBuf buf = Unpooled.buffer();
        avp.encode(buf);

        assertEquals(16, buf.readableBytes());
        assertEquals(264, buf.getInt(0));
        assertEquals(0x40, buf.getUnsignedByte(4));       // M flag only
        assertEquals(13, buf.getInt(4) & 0xFFFFFF);       // length excludes padding
        assertEquals(0, buf.getByte(13));                 // zero padding
        assertEquals(0, buf.getByte(15));

        Avp decoded = Avp.decode(buf);
        assertEquals("abcde", decoded.asUtf8());
        assertEquals(0, buf.readableBytes(), "padding must be consumed");
    }

    @Test
    void vendorSpecificAvpCarriesVendorIdAndFlag() {
        Avp avp = new Avp(1000, 10415, true, new byte[] {1, 2, 3, 4});
        ByteBuf buf = Unpooled.buffer();
        avp.encode(buf);

        assertEquals(0xC0, buf.getUnsignedByte(4));       // V + M
        assertEquals(16, buf.getInt(4) & 0xFFFFFF);
        assertEquals(10415, buf.getInt(8));
        Avp decoded = Avp.decode(buf);
        assertEquals(10415, decoded.vendorId());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, decoded.data());
    }

    @Test
    void unsignedNumbersRoundTrip() {
        assertEquals(4_000_000_000L, Avp.unsigned32(1, 4_000_000_000L).asUnsigned32());
        assertEquals(Long.MAX_VALUE, Avp.unsigned64(2, Long.MAX_VALUE).asUnsigned64());
    }

    @Test
    void groupedAvpRoundTripsWithNestedPadding() {
        Avp grouped = Avp.grouped(443,
                Avp.unsigned32(450, 0),
                Avp.utf8(444, "919876543210"));           // 12 bytes data -> already aligned
        ByteBuf buf = Unpooled.buffer();
        grouped.encode(buf);
        Avp decoded = Avp.decode(buf);

        var children = decoded.asGrouped();
        assertEquals(2, children.size());
        assertEquals(0, children.get(0).asUnsigned32());
        assertEquals("919876543210", children.get(1).asUtf8());
    }

    @Test
    void truncatedAvpIsRejected() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(264);
        buf.writeInt((0x40 << 24) | 40); // claims 40 bytes but none follow
        assertThrows(IllegalArgumentException.class, () -> Avp.decode(buf));
    }
}
