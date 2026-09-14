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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 极简 Modbus TCP 从站模拟器（仅覆盖测试所需的三个功能码）。
 *
 * <p><b>刻意不复用被依赖的协议库</b>：如果用 digitalpetri 自己的 {@code ModbusTcpServer} 做对端，
 * 客户端的帧解析错误可能被同源的编码逻辑"对冲"掉（同源同错）。这里手写 MBAP 帧处理，
 * 使被测客户端面对一个<b>独立实现</b>——这是更有价值的验证。</p>
 *
 * <p>支持：FC01 读线圈、FC03 读保持寄存器、FC06 写单寄存器。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
final class ModbusTcpTestServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ModbusTcpTestServer.class);

    /** MBAP 头长度：事务号(2) + 协议号(2) + 长度(2) + 单元号(1)。 */
    private static final int MBAP_HEADER_LENGTH = 7;

    private static final int EXCEPTION_ILLEGAL_FUNCTION = 0x01;

    private static final int EXCEPTION_ILLEGAL_DATA_ADDRESS = 0x02;

    private static final int EXCEPTION_FLAG = 0x80;

    /** 每个从站的寄存器/线圈镜像：unitId -> 偏移 -> 值。 */
    private final ConcurrentMap<Integer, ConcurrentMap<Integer, Integer>> registers = new ConcurrentHashMap<>();

    private final ConcurrentMap<Integer, ConcurrentMap<Integer, Boolean>> coils = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(true);

    private ServerSocket serverSocket;

    private Thread acceptThread;

    void start() throws IOException {
        serverSocket = new ServerSocket(0);
        acceptThread = Thread.ofPlatform().daemon(true).name("modbus-test-server").start(this::acceptLoop);
        log.debug("[test] modbus tcp test server listening on {}", serverSocket.getLocalPort());
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /**
     * 预置寄存器值。
     *
     * @param unitId 从站地址
     * @param offset 偏移
     * @param value  16 位值
     */
    void setRegister(int unitId, int offset, int value) {
        registers.computeIfAbsent(unitId, ignored -> new ConcurrentHashMap<>()).put(offset, value);
    }

    /**
     * 读取寄存器值。
     *
     * @param unitId 从站地址
     * @param offset 偏移
     * @return 值；未设置时为 0
     */
    int register(int unitId, int offset) {
        ConcurrentMap<Integer, Integer> map = registers.get(unitId);
        return map == null ? 0 : map.getOrDefault(offset, 0);
    }

    /**
     * 预置线圈状态。
     *
     * @param unitId 从站地址
     * @param offset 偏移
     * @param value  状态
     */
    void setCoil(int unitId, int offset, boolean value) {
        coils.computeIfAbsent(unitId, ignored -> new ConcurrentHashMap<>()).put(offset, value);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException ex) {
                if (running.get()) {
                    throw new IllegalStateException("modbus test server accept failed", ex);
                }
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket;
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            while (running.get()) {
                byte[] header = in.readNBytes(MBAP_HEADER_LENGTH);
                if (header.length < MBAP_HEADER_LENGTH) {
                    return;
                }
                // MBAP 的 length 字段 = unitId(1) + PDU 长度
                int length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
                byte[] pdu = in.readNBytes(length - 1);
                int unitId = header[6] & 0xFF;
                byte[] response = handle(unitId, pdu);
                out.write(header[0]);
                out.write(header[1]);
                out.write(0);
                out.write(0);
                out.write(((response.length + 1) >> 8) & 0xFF);
                out.write((response.length + 1) & 0xFF);
                // 响应同样要带 unitId，否则客户端无法匹配事务
                out.write(unitId);
                out.write(response);
                out.flush();
            }
        } catch (IOException ex) {
            // 客户端关闭连接属正常路径
        }
    }

    private byte[] handle(int unitId, byte[] pdu) {
        if (pdu.length == 0) {
            return exception(0x00, EXCEPTION_ILLEGAL_FUNCTION);
        }
        int functionCode = pdu[0] & 0xFF;
        return switch (functionCode) {
            case 0x01 -> readCoils(unitId, pdu);
            case 0x03 -> readHoldingRegisters(unitId, pdu);
            case 0x06 -> writeSingleRegister(unitId, pdu);
            default -> exception(functionCode, EXCEPTION_ILLEGAL_FUNCTION);
        };
    }

    private byte[] readCoils(int unitId, byte[] pdu) {
        int start = readUnsignedShort(pdu, 1);
        int quantity = readUnsignedShort(pdu, 3);
        ConcurrentMap<Integer, Boolean> map = coils.getOrDefault(unitId, new ConcurrentHashMap<>());
        int byteCount = (quantity + 7) / 8;
        byte[] response = new byte[2 + byteCount];
        response[0] = 0x01;
        response[1] = (byte) byteCount;
        for (int index = 0; index < quantity; index++) {
            if (Boolean.TRUE.equals(map.get(start + index))) {
                response[2 + index / 8] |= (byte) (1 << (index % 8));
            }
        }
        return response;
    }

    private byte[] readHoldingRegisters(int unitId, byte[] pdu) {
        int start = readUnsignedShort(pdu, 1);
        int quantity = readUnsignedShort(pdu, 3);
        if (quantity <= 0 || quantity > ModbusRegisterType.MAX_REGISTER_QUANTITY) {
            return exception(0x03, EXCEPTION_ILLEGAL_DATA_ADDRESS);
        }
        ConcurrentMap<Integer, Integer> map = registers.getOrDefault(unitId, new ConcurrentHashMap<>());
        byte[] response = new byte[2 + quantity * 2];
        response[0] = 0x03;
        response[1] = (byte) (quantity * 2);
        for (int index = 0; index < quantity; index++) {
            int value = map.getOrDefault(start + index, 0) & 0xFFFF;
            response[2 + index * 2] = (byte) ((value >> 8) & 0xFF);
            response[3 + index * 2] = (byte) (value & 0xFF);
        }
        return response;
    }

    private byte[] writeSingleRegister(int unitId, byte[] pdu) {
        int offset = readUnsignedShort(pdu, 1);
        int value = readUnsignedShort(pdu, 3);
        registers.computeIfAbsent(unitId, ignored -> new ConcurrentHashMap<>()).put(offset, value);
        // FC06 的响应就是回显请求
        return new byte[] {0x06, pdu[1], pdu[2], pdu[3], pdu[4]};
    }

    private static byte[] exception(int functionCode, int code) {
        return new byte[] {(byte) (functionCode | EXCEPTION_FLAG), (byte) code};
    }

    private static int readUnsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    @Override
    public void close() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to close modbus test server", ex);
        }
    }
}
