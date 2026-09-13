/*
 * Copyright (c) 2024-present ypbin-iot-starter authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ypbin.iot.transport;

import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.protocol.DeviceSession;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty 传输底座：协议无关的客户端链路工厂。
 *
 * <p><b>它只负责「怎么把字节收到、怎么把字节发出去」，不理解任何协议语义</b>——
 * 帧的定界由 {@link FramingSpec} 决定，业务语义由协议模块的 {@link DeviceSession} 实现决定。</p>
 *
 * <p>关键选项（与 DESIGN §5.2 对应）：</p>
 * <ul>
 *   <li>{@code TCP_NODELAY=true}：工业报文小，Nagle 算法会引入 40ms 级延迟；</li>
 *   <li>{@code SO_KEEPALIVE=true}：兜底对端静默掉线；</li>
 *   <li>空闲检测：可选注入 {@link IdleStateHandler}；</li>
 *   <li>写水位：沿用 Netty 默认 {@code WriteBufferWaterMark} 提供背压信号。</li>
 * </ul>
 *
 * <p><b>M0 实现说明</b>：当前使用 {@link NioEventLoopGroup}。Linux 生产环境应切
 * {@code EpollEventLoopGroup} 以获得 {@code SO_REUSEPORT} 与更低的系统调用开销
 * （DESIGN §5.2 N2/N3）；该切换在通过 1 万连接门禁、开始冲击 10 万连接时随压测引入。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class NettyTransport implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NettyTransport.class);

    private static final String THREAD_NAME = "ypbin-iot-netty";

    private static final long SHUTDOWN_QUIET_PERIOD_SECONDS = 0L;

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final EventLoopGroup group;

    private final FramingSpec framingSpec;

    /**
     * 创建传输底座。
     *
     * @param workerThreads worker 线程数；非正数时取 {@code 2 × CPU 核数}
     * @param framingSpec   帧定界规格
     */
    public NettyTransport(int workerThreads, FramingSpec framingSpec) {
        int threads = workerThreads <= 0
                ? Math.max(2, Runtime.getRuntime().availableProcessors() * 2)
                : workerThreads;
        this.group = new NioEventLoopGroup(threads, new DefaultThreadFactory(THREAD_NAME));
        this.framingSpec = Objects.requireNonNull(framingSpec, "framingSpec must not be null");
        log.debug("[ypbin-iot] netty transport created with {} worker threads.", threads);
    }

    /**
     * 打开一条 TCP 链路。
     *
     * @param spec           连接规格
     * @param idleInterval   空闲检测间隔；{@link Duration#ZERO} 表示不启用
     * @param sessionFactory 由协议模块提供的会话工厂（拿到链路后构造自己的 {@link DeviceSession}）
     * @param <S>            会话类型
     * @return 链路就绪后完成的 Stage；建链失败时以 {@link ConnectionException} 异常完成
     */
    public <S extends DeviceSession> CompletionStage<NettyChannelConnection<S>> connect(
            ConnectionSpec spec, Duration idleInterval, Function<NettyChannelConnection<S>, S> sessionFactory) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(sessionFactory, "sessionFactory must not be null");
        Endpoint endpoint = spec.endpoint();
        String host = endpoint.host();
        int port = endpoint.port();
        if (host == null || host.isEmpty() || port <= 0) {
            return CompletableFuture.failedFuture(new ConnectionException(spec.connectionId(),
                    IotMessageKeys.CONFIG_INVALID, "endpoint requires host and port: " + endpoint.uri()));
        }
        CompletableFuture<NettyChannelConnection<S>> result = new CompletableFuture<>();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) spec.connectTimeout().toMillis())
                .handler(channelInitializer(spec, idleInterval, sessionFactory, result));
        bootstrap.connect(new InetSocketAddress(host, port)).addListener(future -> {
            if (!future.isSuccess()) {
                result.completeExceptionally(new ConnectionException(spec.connectionId(),
                        future.cause(), IotMessageKeys.CONNECTION_FAILED, host, port));
            }
        });
        return result;
    }

    /**
     * 当前帧定界规格。
     *
     * @return 规格
     */
    public FramingSpec framingSpec() {
        return framingSpec;
    }

    @Override
    public void close() {
        group.shutdownGracefully(SHUTDOWN_QUIET_PERIOD_SECONDS, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        log.debug("[ypbin-iot] netty transport closed.");
    }

    private <S extends DeviceSession> ChannelInitializer<SocketChannel> channelInitializer(
            ConnectionSpec spec, Duration idleInterval,
            Function<NettyChannelConnection<S>, S> sessionFactory,
            CompletableFuture<NettyChannelConnection<S>> result) {
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel channel) {
                if (idleInterval != null && !idleInterval.isZero() && !idleInterval.isNegative()) {
                    channel.pipeline().addLast(new IdleStateHandler(0, 0,
                            idleInterval.toMillis(), TimeUnit.MILLISECONDS));
                }
                addFraming(channel, framingSpec);
                channel.pipeline().addLast(new ByteArrayEncoder());
                channel.pipeline().addLast(new TransportHandler<>(spec, result, sessionFactory));
            }
        };
    }

    private static void addFraming(Channel channel, FramingSpec spec) {
        if (spec == null || spec.mode() == FramingMode.NONE) {
            return;
        }
        if (spec.mode() == FramingMode.LENGTH_FIELD) {
            channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(spec.maxFrameLength(),
                    spec.lengthFieldOffset(), spec.lengthFieldLength(), spec.lengthAdjustment(),
                    spec.initialBytesToStrip()));
            return;
        }
        if (spec.mode() == FramingMode.DELIMITER) {
            byte[] delimiter = spec.delimiter();
            ByteBuf buffer = channel.alloc().buffer(delimiter.length);
            buffer.writeBytes(delimiter);
            channel.pipeline().addLast(new DelimiterBasedFrameDecoder(spec.maxFrameLength(), buffer));
            return;
        }
        throw new IllegalArgumentException("unsupported framing mode: " + spec.mode());
    }

    /**
     * 传输层 ChannelHandler：把收到的字节帧交给链路，并在链路就绪后装配协议会话。
     *
     * @param <S> 会话类型
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class TransportHandler<S extends DeviceSession>
            extends SimpleChannelInboundHandler<ByteBuf> {

        private final ConnectionSpec spec;

        private final CompletableFuture<NettyChannelConnection<S>> result;

        private final Function<NettyChannelConnection<S>, S> sessionFactory;

        private NettyChannelConnection<S> connection;

        private TransportHandler(ConnectionSpec spec, CompletableFuture<NettyChannelConnection<S>> result,
                Function<NettyChannelConnection<S>, S> sessionFactory) {
            this.spec = spec;
            this.result = result;
            this.sessionFactory = sessionFactory;
        }

        @Override
        public void channelActive(ChannelHandlerContext context) {
            connection = new NettyChannelConnection<>(spec, context.channel(), sessionFactory);
            result.complete(connection);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
            byte[] payload = new byte[message.readableBytes()];
            message.readBytes(payload);
            NettyChannelConnection<S> current = connection;
            if (current != null) {
                current.onFrame(payload);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            NettyChannelConnection<S> current = connection;
            if (current != null) {
                current.onChannelInactive();
            } else {
                result.completeExceptionally(
                        new ConnectionException(spec.connectionId(), IotMessageKeys.CONNECTION_CLOSED));
            }
            context.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            log.error("[ypbin-iot] transport error on connection {}", spec.connectionId(), cause);
            NettyChannelConnection<S> current = connection;
            if (current != null) {
                current.onError(cause);
            } else {
                result.completeExceptionally(new ConnectionException(spec.connectionId(), cause,
                        IotMessageKeys.CONNECTION_FAILED));
            }
            context.close();
        }
    }
}
