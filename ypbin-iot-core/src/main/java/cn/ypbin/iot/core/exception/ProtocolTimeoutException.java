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
package cn.ypbin.iot.core.exception;

import java.time.Duration;

/**
 * 请求超时。
 *
 * <p>超时是工业现场最高频的错误，因此单独成类以便框架做熔断判定与降频决策。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public class ProtocolTimeoutException extends IotException {

    private static final long serialVersionUID = 1L;

    /** iot.common.protocol.timeout 消息键。 */
    public static final String MESSAGE_KEY = "iot.common.protocol.timeout";

    private final String deviceId;

    private final String address;

    private final Duration timeout;

    /**
     * 构造超时异常。
     *
     * @param deviceId 设备标识
     * @param address  发生超时的点位地址
     * @param timeout  超时阈值
     */
    public ProtocolTimeoutException(String deviceId, String address, Duration timeout) {
        super(MESSAGE_KEY, deviceId, address, timeout);
        this.deviceId = deviceId;
        this.address = address;
        this.timeout = timeout;
    }

    /**
     * 发生超时的设备。
     *
     * @return 设备标识
     */
    public String getDeviceId() {
        return deviceId;
    }

    /**
     * 发生超时的地址，用于定位是哪个点位拖慢的。
     *
     * @return 点位地址
     */
    public String getAddress() {
        return address;
    }

    /**
     * 超时阈值。
     *
     * @return 超时阈值
     */
    public Duration getTimeout() {
        return timeout;
    }
}
