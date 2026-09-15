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
package cn.ypbin.iot.it;

import cn.ypbin.iot.core.protocol.ProtocolCode;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 集成测试用的宿主应用。
 *
 * <p>只做一件事：让 Spring Boot 的自动装配生效。设备台账、建链参数、数据出口
 * 都由测试自己的 {@code @TestConfiguration} 提供 —— 这正是真实宿主需要实现的三件事。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
@SpringBootApplication
public class ItApplication {

    /** 集成测试里使用的 MQTT 协议码。 */
    static final ProtocolCode MQTT = ProtocolCode.of("mqtt");

    /** 集成测试里使用的设备标识。 */
    static final String DEVICE_ID = "it-mqtt-device";

    /** 集成测试里使用的链路标识。 */
    static final String CONNECTION_ID = "it-mqtt-link";

    /** 集成测试里订阅/发布的主题。 */
    static final String TOPIC = "factory/line1/temp";
}
