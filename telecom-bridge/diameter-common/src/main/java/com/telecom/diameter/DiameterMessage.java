package com.telecom.diameter;

import static com.telecom.diameter.DiameterConstants.*;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;

/**
 * A Diameter message (RFC 6733 section 3): 20 byte header followed by AVPs.
 *
 * <pre>
 * | Version (1) | Message Length (3)                    |
 * | R P E T r r r r | Command Code (3)                    |
 * | Application-ID (4)                                    |
 * | Hop-by-Hop Identifier (4)                             |
 * | End-to-End Identifier (4)                             |
 * | AVPs ...
 * </pre>
 */
public record DiameterMessage(int flags, int commandCode, int applicationId,
                              int hopByHop, int endToEnd, List<Avp> avps) {

    public static final int VERSION = 1;
    public static final int HEADER_LENGTH = 20;

    public boolean isRequest() {
        return (flags & FLAG_REQUEST) != 0;
    }

    public Optional<Avp> find(int avpCode) {
        return Avp.find(avps, avpCode);
    }

    public long resultCode() {
        return find(AVP_RESULT_CODE).map(Avp::asUnsigned32).orElse(-1L);
    }

    public int encodedLength() {
        int len = HEADER_LENGTH;
        for (Avp a : avps) {
            len += a.paddedLength();
        }
        return len;
    }

    public void encode(ByteBuf out) {
        out.writeByte(VERSION);
        out.writeMedium(encodedLength());
        out.writeByte(flags);
        out.writeMedium(commandCode);
        out.writeInt(applicationId);
        out.writeInt(hopByHop);
        out.writeInt(endToEnd);
        for (Avp a : avps) {
            a.encode(out);
        }
    }

    /** Decodes exactly one complete frame (as produced by the length-field frame decoder). */
    public static DiameterMessage decode(ByteBuf in) {
        int version = in.readUnsignedByte();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported Diameter version " + version);
        }
        in.readUnsignedMedium(); // length, already used for framing
        int flags = in.readUnsignedByte();
        int command = in.readUnsignedMedium();
        int app = in.readInt();
        int hbh = in.readInt();
        int e2e = in.readInt();
        return new DiameterMessage(flags, command, app, hbh, e2e, Avp.decodeAll(in));
    }

    @Override
    public String toString() {
        return "Diameter[cmd=" + commandCode + (isRequest() ? " R" : " A") + " app=" + applicationId
                + " hbh=" + Integer.toUnsignedString(hopByHop) + " e2e=" + Integer.toUnsignedString(endToEnd)
                + " avps=" + avps.size() + "]";
    }
}
