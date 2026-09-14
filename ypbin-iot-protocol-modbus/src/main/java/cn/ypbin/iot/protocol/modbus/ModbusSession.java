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
package cn.ypbin.iot.protocol.modbus;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.exception.ProtocolException;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.PointWriteStatus;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.util.Stages;
import cn.ypbin.iot.runtime.subscription.PollingSubscriptionManager;
import com.digitalpetri.modbus.client.ModbusClient;
import com.digitalpetri.modbus.pdu.ModbusResponsePdu;
import com.digitalpetri.modbus.pdu.ReadCoilsRequest;
import com.digitalpetri.modbus.pdu.ReadCoilsResponse;
import com.digitalpetri.modbus.pdu.ReadDiscreteInputsRequest;
import com.digitalpetri.modbus.pdu.ReadDiscreteInputsResponse;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadInputRegistersResponse;
import com.digitalpetri.modbus.pdu.WriteSingleCoilRequest;
import com.digitalpetri.modbus.pdu.WriteSingleRegisterRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modbus 设备会话：绑定到链路上的一个从站（unitId）。
 *
 * <p>能力的实现方式：</p>
 * <ul>
 *   <li>{@code read}：按寄存器区分组、按协议上限（寄存器 125 / 线圈 2000）分块，
 *       每块一次请求，最后按调用方给出的地址顺序合并结果；</li>
 *   <li>{@code write}：线圈走单线圈写、保持寄存器走单寄存器写；输入寄存器与离散输入只读，
 *       调用写会得到<b>逐项失败</b>而不是整批异常；</li>
 *   <li>{@code subscribe}：委托框架的 {@link PollingSubscriptionManager}（Modbus 无推送能力，
 *       声明的是 {@code SUBSCRIBE_POLLING}）。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-13
 */
final class ModbusSession implements DeviceSession {

    private static final Logger log = LoggerFactory.getLogger(ModbusSession.class);

    private final String sessionId;

    private final DeviceSpec device;

    private final ModbusConnection connection;

    private final AdapterContext context;

    private final PollingSubscriptionManager polling;

    private final int unitId;

    private final Instant boundAt;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建会话。
     *
     * @param device     设备规格（{@code localAddress} 即 unitId）
     * @param connection 物理链路
     * @param context    适配器上下文
     * @param polling    轮询订阅管理器
     */
    ModbusSession(DeviceSpec device, ModbusConnection connection, AdapterContext context,
            PollingSubscriptionManager polling, int defaultUnitId) {
        this.device = device;
        this.connection = connection;
        this.context = context;
        this.polling = polling;
        this.unitId = parseUnitId(device, defaultUnitId);
        this.boundAt = context.clock().instant();
        this.sessionId = device.deviceId() + "@" + connection.connectionId() + "#" + unitId;
    }

    private static int parseUnitId(DeviceSpec device, int defaultUnitId) {
        String local = device.localAddress();
        if (local == null || local.isBlank()) {
            return defaultUnitId;
        }
        try {
            int unitId = Integer.parseInt(local.trim());
            if (unitId < 0 || unitId > ModbusAdapter.MAX_UNIT_ID) {
                throw new ProtocolException(ModbusAdapter.MSG_UNIT_ID_INVALID, device.deviceId(), local);
            }
            return unitId;
        } catch (NumberFormatException ex) {
            throw new ProtocolException(ex, ModbusAdapter.MSG_UNIT_ID_INVALID, device.deviceId(), local);
        }
    }

    /**
     * 从站地址（保活探测需要）。
     *
     * @return unitId
     */
    int unitId() {
        return unitId;
    }

    String localAddressKey() {
        return String.valueOf(unitId);
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public DeviceSpec device() {
        return device;
    }

    @Override
    public String connectionId() {
        return connection.connectionId();
    }

    @Override
    public SessionState state() {
        return closed.get() ? SessionState.CLOSED : connection.state();
    }

    @Override
    public Instant boundAt() {
        return boundAt;
    }

    @Override
    public CompletionStage<ReadResult> read(ReadRequest request) {
        Instant started = context.clock().instant();
        Map<PointAddress, ModbusAddress> parsed = new LinkedHashMap<>();
        Map<PointAddress, PointValue> collected = new LinkedHashMap<>();
        Instant now = context.clock().instant();
        for (PointAddress address : request.addresses()) {
            try {
                parsed.put(address, ModbusAddressCodec.parse(address.raw()));
            } catch (RuntimeException ex) {
                // 地址拼错只让该点位失败：否则点位表里一个错别字会让整台设备的采集全灭
                collected.put(address, PointValue.bad(address, Quality.CONFIG_ERROR,
                        ModbusAdapter.MSG_ADDRESS_INVALID, now));
                context.metrics().recordError(ModbusAdapter.MSG_ADDRESS_INVALID);
            }
        }
        List<Chunk> chunks = chunk(parsed);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Chunk chunk : chunks) {
            chain = chain.thenCompose(ignored -> executeChunk(chunk, collected, request));
        }
        return chain.thenApply(ignored -> {
            // SPI §4.2：整条链路不可用时必须异常完成，否则宿主（与框架的退避逻辑）
            // 无法区分「个别点位坏」与「链路已死」——后者会表现为全速轮询永远返回 BAD。
            if (!collected.isEmpty() && collected.values().stream().noneMatch(PointValue::isGood)
                    && !connection.state().isUsable()) {
                throw new ProtocolException(ModbusAdapter.MSG_CONNECTION_INACTIVE, device.deviceId());
            }
            Instant finished = context.clock().instant();
            List<PointValue> values = new ArrayList<>(request.addresses().size());
            for (PointAddress address : request.addresses()) {
                PointValue value = collected.get(address);
                values.add(value != null ? value : PointValue.bad(address, Quality.BAD,
                        ModbusAdapter.MSG_NO_RESULT, finished));
            }
            Duration elapsed = Duration.between(started, finished);
            context.metrics().recordRead(elapsed, true);
            return new ReadResult(values, elapsed);
        });
    }

    private CompletionStage<Void> executeChunk(Chunk chunk, Map<PointAddress, PointValue> collected,
            ReadRequest request) {
        ModbusClient client = connection.client();
        int start = chunk.start();
        int quantity = chunk.quantity();
        Duration timeout = request.timeout();
        if (chunk.type().isBitType()) {
            CompletionStage<? extends ModbusResponsePdu> stage = chunk.type() == ModbusRegisterType.COIL
                    ? client.readCoilsAsync(unitId, new ReadCoilsRequest(start, quantity))
                    : client.readDiscreteInputsAsync(unitId, new ReadDiscreteInputsRequest(start, quantity));
            return stage.handle((response, error) -> {
                if (error != null) {
                    markChunkFailed(chunk, collected, error, timeout);
                    return null;
                }
                // 线圈与离散输入在响应里是「一位一个线圈」的位打包字节，需要解位
                byte[] packed = chunk.type() == ModbusRegisterType.COIL
                        ? ((ReadCoilsResponse) response).coils()
                        : ((ReadDiscreteInputsResponse) response).inputs();
                int expectedBytes = (quantity + 7) / 8;
                for (Map.Entry<PointAddress, ModbusAddress> entry : chunk.addresses().entrySet()) {
                    int index = entry.getValue().offset() - start;
                    if (packed == null || index < 0 || index / 8 >= packed.length) {
                        // 响应比请求短：缺失的线圈**不能当成 false**（那是编造数据），必须标 BAD
                        collected.put(entry.getKey(), PointValue.bad(entry.getKey(), Quality.BAD,
                                ModbusAdapter.MSG_RESPONSE_TOO_SHORT, context.clock().instant()));
                        continue;
                    }
                    boolean value = unpackBit(packed, index);
                    collected.put(entry.getKey(), PointValue.good(entry.getKey(), value,
                            context.clock().instant()));
                }
                if (packed.length < expectedBytes) {
                    context.metrics().recordError(ModbusAdapter.MSG_RESPONSE_TOO_SHORT);
                }
                return null;
            });
        }
        CompletionStage<? extends ModbusResponsePdu> stage = chunk.type() == ModbusRegisterType.HOLDING_REGISTER
                ? client.readHoldingRegistersAsync(unitId, new ReadHoldingRegistersRequest(start, quantity))
                : client.readInputRegistersAsync(unitId, new ReadInputRegistersRequest(start, quantity));
        return stage.handle((response, error) -> {
            if (error != null) {
                markChunkFailed(chunk, collected, error, timeout);
                return null;
            }
            byte[] registers = chunk.type() == ModbusRegisterType.HOLDING_REGISTER
                    ? ((ReadHoldingRegistersResponse) response).registers()
                    : ((ReadInputRegistersResponse) response).registers();
            for (Map.Entry<PointAddress, ModbusAddress> entry : chunk.addresses().entrySet()) {
                int index = entry.getValue().offset() - start;
                int byteIndex = index * 2;
                if (byteIndex < 0 || byteIndex + 1 >= registers.length) {
                    collected.put(entry.getKey(), PointValue.bad(entry.getKey(), Quality.BAD,
                            ModbusAdapter.MSG_RESPONSE_TOO_SHORT, context.clock().instant()));
                    continue;
                }
                // Modbus 寄存器是 16 位大端无符号
                int value = ((registers[byteIndex] & 0xFF) << 8) | (registers[byteIndex + 1] & 0xFF);
                collected.put(entry.getKey(), PointValue.good(entry.getKey(), value,
                        context.clock().instant()));
            }
            return null;
        });
    }

    /**
     * 把一个分块的失败<b>逐点位</b>标记，而不是让整次读异常完成。
     *
     * <p>协议规范要求：部分点位失败不得影响其余点位；只有整条链路不可用才异常完成。</p>
     */
    private void markChunkFailed(Chunk chunk, Map<PointAddress, PointValue> collected, Throwable error,
            Duration timeout) {
        // 必须先用 Stages.unwrap 剥掉 CompletionException 包装：协议库的 thenApply 链会把
        // 直接的 TimeoutException 包一层，裸 instanceof 恒为 false（该分支此前从未生效）
        boolean timeoutError = Stages.unwrap(error) instanceof TimeoutException;
        Instant now = context.clock().instant();
        for (PointAddress address : chunk.addresses().keySet()) {
            collected.put(address, PointValue.bad(address, Quality.BAD,
                    timeoutError ? ModbusAdapter.MSG_TIMEOUT : ModbusAdapter.MSG_CHUNK_FAILED, now));
        }
        context.metrics().recordError(timeoutError ? ModbusAdapter.MSG_TIMEOUT : ModbusAdapter.MSG_CHUNK_FAILED);
        log.debug("[ypbin-iot] modbus read chunk failed for device {} (unit {}): {}",
                device.deviceId(), unitId, error.toString());
        if (timeoutError && timeout != null) {
            log.debug("[ypbin-iot] chunk timeout was {}", timeout);
        }
    }

    private static List<Chunk> chunk(Map<PointAddress, ModbusAddress> parsed) {
        Map<ModbusRegisterType, List<Map.Entry<PointAddress, ModbusAddress>>> byType = new LinkedHashMap<>();
        for (Map.Entry<PointAddress, ModbusAddress> entry : parsed.entrySet()) {
            byType.computeIfAbsent(entry.getValue().type(), ignored -> new ArrayList<>()).add(entry);
        }
        List<Chunk> chunks = new ArrayList<>();
        for (Map.Entry<ModbusRegisterType, List<Map.Entry<PointAddress, ModbusAddress>>> group
                : byType.entrySet()) {
            List<Map.Entry<PointAddress, ModbusAddress>> entries = group.getValue();
            entries.sort(Comparator.comparingInt(entry -> entry.getValue().offset()));
            int maxQuantity = group.getKey().maxQuantity();
            Map<PointAddress, ModbusAddress> current = new LinkedHashMap<>();
            int start = -1;
            int end = -1;
            for (Map.Entry<PointAddress, ModbusAddress> entry : entries) {
                int offset = entry.getValue().offset();
                if (start < 0) {
                    start = offset;
                    end = offset;
                } else if (offset - start + 1 > maxQuantity) {
                    chunks.add(new Chunk(group.getKey(), start, end - start + 1, current));
                    current = new LinkedHashMap<>();
                    start = offset;
                    end = offset;
                } else {
                    end = Math.max(end, offset);
                }
                current.put(entry.getKey(), entry.getValue());
            }
            if (!current.isEmpty()) {
                chunks.add(new Chunk(group.getKey(), start, end - start + 1, current));
            }
        }
        return chunks;
    }

    @Override
    public CompletionStage<WriteResult> write(WriteRequest request) {
        Instant started = context.clock().instant();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        List<PointWriteStatus> statuses = new ArrayList<>();
        for (PointWrite write : request.writes()) {
            chain = chain.thenCompose(ignored -> writeOne(write, statuses));
        }
        return chain.thenApply(ignored -> {
            Duration elapsed = Duration.between(started, context.clock().instant());
            context.metrics().recordWrite(elapsed, statuses.stream().allMatch(PointWriteStatus::success));
            return new WriteResult(statuses, elapsed);
        });
    }

    private CompletionStage<Void> writeOne(PointWrite write, List<PointWriteStatus> statuses) {
        ModbusAddress address;
        try {
            address = ModbusAddressCodec.parse(write.address().raw());
        } catch (RuntimeException ex) {
            statuses.add(PointWriteStatus.fail(write.address(), ModbusAdapter.MSG_ADDRESS_INVALID));
            return CompletableFuture.completedFuture(null);
        }
        if (!address.type().isWritable()) {
            statuses.add(PointWriteStatus.fail(write.address(), ModbusAdapter.MSG_NOT_WRITABLE));
            return CompletableFuture.completedFuture(null);
        }
        int numeric;
        try {
            numeric = toNumeric(write.value());
        } catch (RuntimeException ex) {
            statuses.add(PointWriteStatus.fail(write.address(), ModbusAdapter.MSG_VALUE_INVALID));
            return CompletableFuture.completedFuture(null);
        }
        ModbusClient client = connection.client();
        CompletionStage<? extends ModbusResponsePdu> stage;
        if (address.type() == ModbusRegisterType.COIL) {
            stage = client.writeSingleCoilAsync(unitId,
                    new WriteSingleCoilRequest(address.offset(), numeric != 0));
        } else {
            stage = client.writeSingleRegisterAsync(unitId,
                    new WriteSingleRegisterRequest(address.offset(), numeric & 0xFFFF));
        }
        return stage.handle((response, error) -> {
            if (error == null) {
                statuses.add(PointWriteStatus.ok(write.address()));
            } else {
                statuses.add(PointWriteStatus.fail(write.address(), ModbusAdapter.MSG_WRITE_FAILED));
                log.debug("[ypbin-iot] modbus write failed for device {} address {}: {}",
                        device.deviceId(), write.address().raw(), error.toString());
            }
            return null;
        });
    }

    /**
     * 从位打包字节里取第 {@code index} 位（低位在前）。
     *
     * @param packed 位打包字节
     * @param index  位序号
     * @return 该位为 1 时返回 {@code true}；越界返回 {@code false}
     */
    private static boolean unpackBit(byte[] packed, int index) {
        if (index < 0 || packed == null) {
            return false;
        }
        int byteIndex = index / 8;
        if (byteIndex >= packed.length) {
            return false;
        }
        return ((packed[byteIndex] >> (index % 8)) & 0x01) == 1;
    }

    private static int toNumeric(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return 1;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return 0;
            }
            return Integer.parseInt(trimmed);
        }
        throw new IllegalArgumentException("unsupported modbus value type");
    }

    @Override
    public CompletionStage<SubscriptionHandle> subscribe(SubscribeRequest request, DataListener listener) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new ProtocolException(ModbusAdapter.MSG_SESSION_CLOSED, device.deviceId()));
        }
        return CompletableFuture.completedFuture(polling.subscribe(this, request, listener));
    }

    @Override
    public CompletionStage<Void> unsubscribe(SubscriptionHandle handle) {
        polling.unsubscribe(handle);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<PingResult> ping() {
        long started = System.nanoTime();
        // 必须查链路状态而不是协议库的 isConnected()：断开是异步的，
        // 刚调用 disconnectAsync 后 isConnected() 仍可能返回 true，导致 ping 误报存活
        if (!closed.get() && connection.state().isUsable()) {
            return CompletableFuture.completedFuture(
                    PingResult.alive(Duration.ofNanos(System.nanoTime() - started).toMillis()));
        }
        return CompletableFuture.completedFuture(PingResult.dead(ModbusAdapter.MSG_CONNECTION_INACTIVE));
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> extensionType) {
        return Optional.empty();
    }

    @Override
    public CompletionStage<Void> close() {
        if (!closed.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        polling.cancelAll(sessionId);
        connection.unregister(this);
        return CompletableFuture.completedFuture(null);
    }

    /** 链路断开时由连接回调：取消本会话的全部订阅。 */
    void onConnectionClosed() {
        closed.set(true);
        polling.cancelAll(sessionId);
    }

    /**
     * 读分块：同类型、同功能码、跨度不超过协议上限的一组地址。
     *
     * @param type     寄存器区类型
     * @param start    起始偏移
     * @param quantity 读取数量
     * @param addresses 该块覆盖的地址映射
     * @author wenbin
     * @since 2026-09-13
     */
    private record Chunk(ModbusRegisterType type, int start, int quantity,
            Map<PointAddress, ModbusAddress> addresses) {
    }
}
