package com.telecom.diameter;

import static com.telecom.diameter.DiameterConstants.*;
import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class DiameterMessageTest {

    private DiameterMessage sampleCcr() {
        return Messages.ccr("gw.local;1;1", "gw.local", "realm.local", "ocs.local",
                "gy@telecom-bridge.local", "919876543210", 1_048_576L, 0, 0xCAFEBABE, 0x01020304);
    }

    @Test
    void headerIsEncodedPerRfc6733() {
        DiameterMessage ccr = sampleCcr();
        ByteBuf buf = Unpooled.buffer();
        ccr.encode(buf);

        assertEquals(1, buf.getUnsignedByte(0));                       // version
        assertEquals(buf.readableBytes(), buf.getUnsignedMedium(1));   // length incl. header
        assertEquals(0xC0, buf.getUnsignedByte(4));                    // R + P
        assertEquals(272, buf.getUnsignedMedium(5));                   // CC command code
        assertEquals(4, buf.getInt(8));                                // Credit-Control application
        assertEquals(0xCAFEBABE, buf.getInt(12));                      // hop-by-hop
        assertEquals(0x01020304, buf.getInt(16));                      // end-to-end
        assertEquals(0, buf.readableBytes() % 4, "message must be 4 byte aligned");
    }

    @Test
    void ccrRoundTripKeepsAvps() {
        ByteBuf buf = Unpooled.buffer();
        sampleCcr().encode(buf);
        DiameterMessage decoded = DiameterMessage.decode(buf);

        assertTrue(decoded.isRequest());
        assertEquals("gw.local;1;1", Messages.sessionId(decoded));
        assertEquals(CC_REQUEST_TYPE_INITIAL, decoded.find(AVP_CC_REQUEST_TYPE).orElseThrow().asUnsigned32());
        var sub = decoded.find(AVP_SUBSCRIPTION_ID).orElseThrow().asGrouped();
        assertEquals("919876543210", Avp.find(sub, AVP_SUBSCRIPTION_ID_DATA).orElseThrow().asUtf8());
    }

    @Test
    void ccaMirrorsIdentifiersAndCarriesGrantedUnits() {
        DiameterMessage ccr = sampleCcr();
        DiameterMessage cca = Messages.cca(ccr, "ocs.local", "realm.local", RESULT_SUCCESS, 500_000L);

        assertFalse(cca.isRequest());
        assertEquals(ccr.hopByHop(), cca.hopByHop());
        assertEquals(ccr.endToEnd(), cca.endToEnd());
        assertEquals(RESULT_SUCCESS, cca.resultCode());
        assertEquals(500_000L, Messages.grantedOctets(cca));
        assertEquals(Messages.sessionId(ccr), Messages.sessionId(cca));
    }

    @Test
    void cerCarriesMandatoryAvps() throws Exception {
        DiameterMessage cer = Messages.cer("gw.local", "realm.local", InetAddress.getLoopbackAddress(), 1, 2);
        assertEquals(CMD_CER, cer.commandCode());
        assertEquals(APP_COMMON, cer.applicationId());
        assertEquals(0, cer.find(AVP_VENDOR_ID).orElseThrow().asUnsigned32());
        assertEquals(APP_CREDIT_CONTROL, cer.find(AVP_AUTH_APPLICATION_ID).orElseThrow().asUnsigned32());
        assertTrue(cer.find(AVP_HOST_IP_ADDRESS).isPresent());
    }

    @Test
    void nettyPipelineReassemblesFragmentedFrames() {
        EmbeddedChannel ch = new EmbeddedChannel(DiameterCodec.newHandlers());
        assertTrue(ch.writeOutbound(sampleCcr()));
        ByteBuf wire = ch.readOutbound();

        ch.writeInbound(wire.readRetainedSlice(10));   // partial frame
        assertNull(ch.readInbound());
        ch.writeInbound(wire);                         // remainder
        DiameterMessage decoded = ch.readInbound();

        assertNotNull(decoded);
        assertEquals(CMD_CCR, decoded.commandCode());
        ch.finishAndReleaseAll();
    }
}
