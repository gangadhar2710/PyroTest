package com.telecom.gateway;

import static com.telecom.diameter.DiameterConstants.*;

import com.telecom.diameter.DiameterCodec;
import com.telecom.diameter.DiameterMessage;
import com.telecom.diameter.Messages;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class DiameterClient {

    private static final Logger log = LoggerFactory.getLogger(DiameterClient.class);

    private final DiameterProperties props;
    private final EventLoopGroup group = new NioEventLoopGroup(2);
    private final Map<Integer, CompletableFuture<DiameterMessage>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger hopByHop = new AtomicInteger(new SecureRandom().nextInt());
    private final AtomicInteger endToEnd = new AtomicInteger((int) (System.currentTimeMillis() / 1000) << 20);
    private final AtomicLong sessionSeq = new AtomicLong();
    private final long bootSeconds = System.currentTimeMillis() / 1000;

    private volatile Channel channel;
    private volatile boolean ready;      // true once CEA(2001) has been received
    private volatile boolean stopping;

    public DiameterClient(DiameterProperties props) {
        this.props = props;
    }

    @PostConstruct
    void start() {
        connect();
    }

    @PreDestroy
    void stop() {
        stopping = true;
        ready = false;
        Channel ch = channel;
        if (ch != null) {
            ch.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
        failAllPending(new GatewayUnavailableException("Gateway shutting down"));
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        log.info("Diameter client stopped");
    }

    public boolean isReady() {
        Channel ch = channel;
        return ready && ch != null && ch.isActive();
    }

    public int inFlight() {
        return pending.size();
    }


    public CompletableFuture<DiameterMessage> sendCcr(String msisdn, long requestedOctets) {
        Channel ch = channel;
        if (!isReady()) {
            return CompletableFuture.failedFuture(new GatewayUnavailableException("Diameter peer not connected"));
        }
        if (!ch.isWritable()) {
            return CompletableFuture.failedFuture(new GatewayUnavailableException("Diameter connection congested"));
        }

        int hbh = hopByHop.incrementAndGet();
        int e2e = endToEnd.incrementAndGet();
        String sessionId = props.originHost() + ";" + bootSeconds + ";" + sessionSeq.incrementAndGet();
        DiameterMessage ccr = Messages.ccr(sessionId, props.originHost(), props.originRealm(),
                props.destinationRealm(), props.serviceContextId(), msisdn, requestedOctets, 0, hbh, e2e);

        CompletableFuture<DiameterMessage> future = new CompletableFuture<>();
        pending.put(hbh, future);
        future.orTimeout(props.requestTimeoutMs(), TimeUnit.MILLISECONDS);
        future.whenComplete((r, t) -> pending.remove(hbh, future));   // always clean up

        ch.writeAndFlush(ccr).addListener((ChannelFutureListener) w -> {
            if (!w.isSuccess()) {
                future.completeExceptionally(new GatewayUnavailableException("Failed to write CCR", w.cause()));
            }
        });
        return future;
    }

    // ------------------------------------------------------------------ connection handling

    private void connect() {
        if (stopping) {
            return;
        }
        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.connectTimeoutMs())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(DiameterCodec.newHandlers());
                        ch.pipeline().addLast(new IdleStateHandler(
                                props.watchdogSeconds() * 3L, props.watchdogSeconds(), 0, TimeUnit.SECONDS));
                        ch.pipeline().addLast(new Handler());
                    }
                });
        log.info("Connecting to Diameter peer {}:{}", props.host(), props.port());
        b.connect(props.host(), props.port()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warn("Diameter connect failed: {}", f.cause().toString());
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (!stopping) {
            group.schedule(this::connect, props.reconnectDelayMs(), TimeUnit.MILLISECONDS);
        }
    }

    private void failAllPending(Throwable cause) {
        pending.values().forEach(f -> f.completeExceptionally(cause));
        pending.clear();
    }

    private final class Handler extends SimpleChannelInboundHandler<DiameterMessage> {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            channel = ctx.channel();
            InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
            log.info("TCP connected {} -> {}, sending CER", local, ctx.channel().remoteAddress());
            ctx.writeAndFlush(Messages.cer(props.originHost(), props.originRealm(), local.getAddress(),
                    hopByHop.incrementAndGet(), endToEnd.incrementAndGet()));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.warn("Diameter connection closed");
            ready = false;
            if (channel == ctx.channel()) {
                channel = null;
            }
            failAllPending(new GatewayUnavailableException("Diameter connection lost"));
            scheduleReconnect();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DiameterMessage msg) {
            switch (msg.commandCode()) {
                case CMD_CER -> {
                    if (msg.resultCode() == RESULT_SUCCESS) {
                        ready = true;
                        log.info("CEA received (2001) - Diameter peer is READY");
                    } else {
                        log.error("CEA rejected, Result-Code={}", msg.resultCode());
                        ctx.close();
                    }
                }
                case CMD_DWR -> {
                    if (msg.isRequest()) {
                        ctx.writeAndFlush(Messages.dwa(msg, props.originHost(), props.originRealm()));
                    } else {
                        log.debug("DWA received");
                    }
                }
                case CMD_CCR -> {
                    CompletableFuture<DiameterMessage> f = pending.remove(msg.hopByHop());
                    if (f != null) {
                        f.complete(msg);
                    } else {
                        log.debug("Late/unknown CCA hop-by-hop={} discarded", Integer.toUnsignedString(msg.hopByHop()));
                    }
                }
                default -> log.warn("Unhandled Diameter message {}", msg);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent e) {
                if (e.state() == IdleState.READER_IDLE) {
                    log.warn("No data from peer for {}s - closing connection", props.watchdogSeconds() * 3L);
                    ctx.close();
                } else if (e.state() == IdleState.WRITER_IDLE && ready) {
                    log.debug("Sending DWR");
                    ctx.writeAndFlush(Messages.dwr(props.originHost(), props.originRealm(),
                            hopByHop.incrementAndGet(), endToEnd.incrementAndGet()));
                }
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Diameter channel error: {}", cause.toString());
            ctx.close();
        }
    }
}
