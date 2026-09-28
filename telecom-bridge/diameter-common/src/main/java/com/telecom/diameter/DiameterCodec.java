package com.telecom.diameter;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;

/** Netty pipeline pieces: TCP stream -> frames -> {@link DiameterMessage} and back. */
public final class DiameterCodec {
    private DiameterCodec() {}

    public static final int MAX_FRAME = 1 << 20;

    /** New (stateful) handler instances; call once per channel. */
    public static ChannelHandler[] newHandlers() {
        return new ChannelHandler[] {new FrameDecoder(), new MessageDecoder(), new MessageEncoder()};
    }

    /** The message length field (bytes 1-3) covers the whole message including the 20 byte header. */
    public static final class FrameDecoder extends LengthFieldBasedFrameDecoder {
        public FrameDecoder() {
            super(MAX_FRAME, 1, 3, -4, 0);
        }
    }

    public static final class MessageDecoder extends MessageToMessageDecoder<ByteBuf> {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            out.add(DiameterMessage.decode(in));
        }
    }

    public static final class MessageEncoder extends MessageToByteEncoder<DiameterMessage> {
        @Override
        protected void encode(ChannelHandlerContext ctx, DiameterMessage msg, ByteBuf out) {
            msg.encode(out);
        }
    }
}
