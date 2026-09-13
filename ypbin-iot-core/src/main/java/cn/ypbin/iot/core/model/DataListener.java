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

/**
 * 订阅数据回调。
 *
 * <p>传入订阅请求的 listener 为 {@code null} 时，数据走框架统一出口
 * （{@link cn.ypbin.iot.core.context.AdapterContext#egress()}）；
 * 传入 listener 时由该 listener 消费，<b>不再走 egress</b>，避免同一份数据被投递两次。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
@FunctionalInterface
public interface DataListener {

    /**
     * 收到一个点位值。
     *
     * @param value 点位值
     */
    void onData(PointValue value);
}
