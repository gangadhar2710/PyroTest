package com.telecom.diameter;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A single Diameter Attribute-Value Pair (RFC 6733 section 4.1).
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           AVP Code                            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V M P r r r r r|                  AVP Length                   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        Vendor-ID (opt)                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Data ... (padded with zeros to a 4 byte boundary)
 * </pre>
 * The AVP Length field does NOT include the padding; the padding is still present on the wire.
 */
public record Avp(int code, long vendorId, boolean mandatory, byte[] data) {

    public static final int FLAG_VENDOR = 0x80;
    public static final int FLAG_MANDATORY = 0x40;

    public boolean hasVendor() {
        return vendorId != 0;
    }

    /** Length as written in the AVP Length field (header + data, no padding). */
    public int length() {
        return 8 + (hasVendor() ? 4 : 0) + data.length;
    }

    /** Length on the wire including padding to a 4-byte boundary. */
    public int paddedLength() {
        return (length() + 3) & ~3;
    }

    public Avp withMandatory(boolean m) {
        return new Avp(code, vendorId, m, data);
    }

    public void encode(ByteBuf out) {
        int flags = (hasVendor() ? FLAG_VENDOR : 0) | (mandatory ? FLAG_MANDATORY : 0);
        out.writeInt(code);
        out.writeInt((flags << 24) | length());
        if (hasVendor()) {
            out.writeInt((int) vendorId);
        }
        out.writeBytes(data);
        out.writeZero(paddedLength() - length());
    }

    public static Avp decode(ByteBuf in) {
        if (in.readableBytes() < 8) {
            throw new IllegalArgumentException("Truncated AVP header");
        }
        int code = in.readInt();
        int flagsAndLen = in.readInt();
        int flags = flagsAndLen >>> 24;
        int len = flagsAndLen & 0xFFFFFF;
        boolean vendor = (flags & FLAG_VENDOR) != 0;
        long vendorId = vendor ? (in.readInt() & 0xFFFFFFFFL) : 0;
        int dataLen = len - 8 - (vendor ? 4 : 0);
        int pad = ((len + 3) & ~3) - len;
        if (dataLen < 0 || in.readableBytes() < dataLen) {
            throw new IllegalArgumentException("Invalid AVP length " + len + " for code " + code);
        }
        byte[] data = new byte[dataLen];
        in.readBytes(data);
        in.skipBytes(Math.min(pad, in.readableBytes()));
        return new Avp(code, vendorId, (flags & FLAG_MANDATORY) != 0, data);
    }

    public static List<Avp> decodeAll(ByteBuf in) {
        List<Avp> list = new ArrayList<>();
        while (in.isReadable()) {
            list.add(decode(in));
        }
        return list;
    }

    // ---- factories (all mandatory by default; use withMandatory(false) where the RFC says MUST NOT) ----

    public static Avp utf8(int code, String value) {
        return new Avp(code, 0, true, value.getBytes(UTF_8));
    }

    public static Avp unsigned32(int code, long value) {
        return new Avp(code, 0, true, ByteBuffer.allocate(4).putInt((int) value).array());
    }

    public static Avp unsigned64(int code, long value) {
        return new Avp(code, 0, true, ByteBuffer.allocate(8).putLong(value).array());
    }

    /** RFC 6733 "Time" type: seconds since 1900-01-01 (NTP epoch). */
    public static Avp time(int code, long unixSeconds) {
        return unsigned32(code, unixSeconds + 2_208_988_800L);
    }

    /** "Address" type: 2 byte address family (1 = IPv4, 2 = IPv6) followed by the raw address. */
    public static Avp address(int code, InetAddress address) {
        byte[] raw = address.getAddress();
        ByteBuffer bb = ByteBuffer.allocate(2 + raw.length);
        bb.putShort((short) (address instanceof Inet4Address ? 1 : 2)).put(raw);
        return new Avp(code, 0, true, bb.array());
    }

    public static Avp grouped(int code, Avp... children) {
        int total = 0;
        for (Avp c : children) {
            total += c.paddedLength();
        }
        ByteBuf buf = Unpooled.buffer(total);
        try {
            for (Avp c : children) {
                c.encode(buf);
            }
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new Avp(code, 0, true, bytes);
        } finally {
            buf.release();
        }
    }

    // ---- accessors ----

    public String asUtf8() {
        return new String(data, UTF_8);
    }

    public long asUnsigned32() {
        return ByteBuffer.wrap(data).getInt() & 0xFFFFFFFFL;
    }

    public long asUnsigned64() {
        return ByteBuffer.wrap(data).getLong();
    }

    public List<Avp> asGrouped() {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            return decodeAll(buf);
        } finally {
            buf.release();
        }
    }

    public static Optional<Avp> find(List<Avp> avps, int code) {
        for (Avp a : avps) {
            if (a.code() == code) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }
}
