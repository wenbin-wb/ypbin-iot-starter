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

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Modbus 寄存器区类型。
 *
 * <p>{@code code} 是地址字符串里使用的类型名（如 {@code holding:0} 中的 {@code holding}），
 * 与功能码一一对应。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
enum ModbusRegisterType {

    /** 线圈：可读可写，功能码 01/05/15。 */
    COIL("coil", "线圈", 1, true),

    /** 离散输入：只读，功能码 02。 */
    DISCRETE_INPUT("discrete", "离散输入", 2, false),

    /** 保持寄存器：可读可写，功能码 03/06/16。 */
    HOLDING_REGISTER("holding", "保持寄存器", 3, true),

    /** 输入寄存器：只读，功能码 04。 */
    INPUT_REGISTER("input", "输入寄存器", 4, false);

    /** 单次请求的最大点数（协议规范硬上限）。 */
    static final int MAX_COIL_QUANTITY = 2000;

    static final int MAX_REGISTER_QUANTITY = 125;

    /** 偏移上限（5 位编号约定）。 */
    static final int MAX_OFFSET = 65535;

    private final String code;

    private final String desc;

    private final int functionCodeBase;

    private final boolean writable;

    ModbusRegisterType(String code, String desc, int functionCodeBase, boolean writable) {
        this.code = code;
        this.desc = desc;
        this.functionCodeBase = functionCodeBase;
        this.writable = writable;
    }

    /**
     * 类型名（地址字符串中使用）。
     *
     * @return 类型名
     */
    String getCode() {
        return code;
    }

    /**
     * 类型描述。
     *
     * @return 描述
     */
    String getDesc() {
        return desc;
    }

    /**
     * 读功能码。
     *
     * @return 读功能码
     */
    int readFunctionCode() {
        return functionCodeBase;
    }

    /**
     * 是否可写。
     *
     * @return 可写返回 {@code true}
     */
    boolean isWritable() {
        return writable;
    }

    /**
     * 单次请求的最大点数。
     *
     * @return 最大点数
     */
    int maxQuantity() {
        return this == COIL || this == DISCRETE_INPUT ? MAX_COIL_QUANTITY : MAX_REGISTER_QUANTITY;
    }

    /**
     * 是否按位寻址（线圈类）。
     *
     * @return 位类型返回 {@code true}
     */
    boolean isBitType() {
        return this == COIL || this == DISCRETE_INPUT;
    }

    /**
     * 按类型名查找（大小写不敏感）。
     *
     * @param code 类型名
     * @return 匹配的类型；无匹配时返回空 Optional
     */
    static Optional<ModbusRegisterType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.code.equals(normalized)).findFirst();
    }

    /**
     * 可用类型名的逗号分隔串，用于错误提示。
     *
     * @return 类型名列表
     */
    static String expectedCodes() {
        return Arrays.stream(values()).map(ModbusRegisterType::getCode).collect(Collectors.joining(", "));
    }
}
