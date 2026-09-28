package com.telecom.simulator;

import static com.telecom.diameter.DiameterConstants.*;

import com.telecom.diameter.DiameterCodec;
import com.telecom.diameter.DiameterMessage;
import com.telecom.diameter.Messages;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DiameterSimulator {

    private static final Logger log = LoggerFactory.getLogger(DiameterSimulator.class);
    private static final String HOST = "ocs.telecom-bridge.local";
    private static final String REALM = "ocs.telecom-bridge.local";
    private static final AtomicLong CCR_COUNT = new AtomicLong();
    private static final long MIN_DELAY_MS = Long.parseLong(System.getenv().getOrDefault("MIN_DELAY_MS", "50"));
    private static final long MAX_DELAY_MS = Long.parseLong(System.getenv().getOrDefault("MAX_DELAY_MS", "100"));

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args.length > 0 ? args[0] : System.getenv().getOrDefault("PORT", "3868"));
        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup workers = new NioEventLoopGroup();
        try {
            Channel server = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(DiameterCodec.newHandlers());
                            ch.pipeline().addLast(new ServerHandler());
                        }
                    })
                    .bind(port).sync().channel();
            log.info("Diameter simulator listening on port {}", port);
            server.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            workers.shutdownGracefully();
        }
    }

    private static final class ServerHandler extends SimpleChannelInboundHandler<DiameterMessage> {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            log.info("Client connected: {}", ctx.channel().remoteAddress());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.info("Client disconnected: {}", ctx.channel().remoteAddress());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DiameterMessage msg) {
            if (!msg.isRequest()) {
                return;
            }
            switch (msg.commandCode()) {
                case CMD_CER -> {
                    InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
                    ctx.writeAndFlush(Messages.cea(msg, HOST, REALM, local.getAddress(), RESULT_SUCCESS));
                    log.info("CER -> CEA (2001)");
                }
                case CMD_DWR -> ctx.writeAndFlush(Messages.dwa(msg, HOST, REALM));
                case CMD_CCR -> {
                    long delayMs = ThreadLocalRandom.current().nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1);   // default 50..100 ms
                    ctx.executor().schedule(() -> {
                        ctx.writeAndFlush(Messages.cca(msg, HOST, REALM, RESULT_SUCCESS, 1_048_576L));
                        long n = CCR_COUNT.incrementAndGet();
                        if (n % 10_000 == 0) {
                            log.info("Served {} CCRs", n);
                        }
                    }, delayMs, TimeUnit.MILLISECONDS);
                }
                case CMD_DPR -> ctx.close();
                default -> log.warn("Unsupported command {}", msg.commandCode());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("Channel error: {}", cause.toString());
            ctx.close();
        }
    }
}
