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
package cn.ypbin.iot.core.model;

import cn.ypbin.iot.core.protocol.BrowseExtension;

/**
 * 测试用扩展接口：继承 {@link BrowseExtension} 但<b>不被</b>描述符声明，
 * 用于验证 {@code supportsExtension} 对未声明类型返回 {@code false}。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface ProtocolCapabilityAlias extends BrowseExtension {
}
