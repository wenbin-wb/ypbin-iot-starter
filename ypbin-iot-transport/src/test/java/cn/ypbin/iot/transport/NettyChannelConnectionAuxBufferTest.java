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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import io.netty.channel.Channel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NettyChannelConnection} 入站辅助缓冲行为测试（UP-12）。
 *
 * <p>锁定语义：辅助缓冲（{@code drainFrames()} 的数据源）是<b>拉模式专用</b>——
 * push 模式宿主（只挂 {@code addFrameListener}、从不 drain）不得入队/计数/告警。
 * 变异点：把「未启用不入队」改回无条件入队 ⇒ push-only 用例（droppedFrames 恒 0）转红。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
class NettyChannelConnectionAuxBufferTest {

    private NettyChannelConnection connection() {
        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        ConnectionSpec spec = new ConnectionSpec("c-1", ProtocolCode.of("tcp"),
                Endpoint.of("tcp://127.0.0.1:1"), Duration.ofSeconds(3), Duration.ofSeconds(3),
                null, null, java.util.Map.of());
        NettyChannelConnection connection = new NettyChannelConnection(spec, channel);
        connection.setInboundCapacity(2);
        return connection;
    }

    private NettyChannelConnection connectionNoCapacityChange() {
        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        ConnectionSpec spec = new ConnectionSpec("c-1", ProtocolCode.of("tcp"),
                Endpoint.of("tcp://127.0.0.1:1"), Duration.ofSeconds(3), Duration.ofSeconds(3),
                null, null, java.util.Map.of());
        return new NettyChannelConnection(spec, channel);
    }

    @Test
    @DisplayName("UP-12：只挂 push 监听、从不 drain 的宿主——入队/计数/告警全部不发生，push 帧一个不少")
    void pushOnlyConsumerGetsAllFramesWithoutOverflowBookkeeping() {
        NettyChannelConnection connection = connection();
        List<byte[]> pushed = new ArrayList<>();
        connection.addFrameListener(pushed::add);

        for (int i = 0; i < 10; i++) {
            connection.onFrame(new byte[]{(byte) i});
        }

        assertThat(pushed).as("push 路径必须收到全部 10 帧").hasSize(10);
        assertThat(connection.droppedFrames())
                .as("无 drain 消费者时不得计数（变异点：无条件入队会让它 > 0）")
                .isZero();
        assertThat(connection.drainFrames())
                .as("缓冲未启用，drain 得到空列表")
                .isEmpty();
    }

    @Test
    @DisplayName("UP-12：确实调用 drainFrames() 后缓冲启用——溢出仍可观测（计数 + 淘汰最老）")
    void drainConsumerActivatesBufferAndOverflowIsObservable() {
        NettyChannelConnection connection = connectionNoCapacityChange();
        connection.setInboundCapacity(2);
        // 启用缓冲
        assertThat(connection.drainFrames()).isEmpty();

        List<byte[]> pushed = new ArrayList<>();
        connection.addFrameListener(pushed::add);
        for (int i = 0; i < 5; i++) {
            connection.onFrame(new byte[]{(byte) i});
        }

        assertThat(pushed).as("push 路径仍收全部帧").hasSize(5);
        assertThat(connection.droppedFrames())
                .as("缓冲启用且溢出：淘汰辅助缓冲副本可观测")
                .isEqualTo(3);
        List<byte[]> drained = connection.drainFrames();
        assertThat(drained).as("保留最近 2 帧（淘汰的是辅助缓冲副本，不是投递丢失）").hasSize(2);
    }

    @Test
    @DisplayName("UP-12：帧监听器异常不得中断其余监听器投递")
    void oneFailingListenerMustNotBreakOthers() {
        NettyChannelConnection connection = connection();
        AtomicInteger good = new AtomicInteger();
        Consumer<byte[]> failing = frame -> {
            throw new IllegalStateException("listener boom");
        };
        connection.addFrameListener(failing);
        connection.addFrameListener(frame -> good.incrementAndGet());

        connection.onFrame(new byte[]{1});
        connection.onFrame(new byte[]{2});

        assertThat(good.get()).isEqualTo(2);
    }
}
