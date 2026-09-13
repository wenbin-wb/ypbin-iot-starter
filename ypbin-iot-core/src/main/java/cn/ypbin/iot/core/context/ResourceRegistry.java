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
package cn.ypbin.iot.core.context;

/**
 * 适配器级资源登记处。
 *
 * <p>登记的资源在适配器关闭时按登记的<b>逆序</b>释放；单个资源释放失败只记录日志
 * 并继续释放其余资源，避免一个坏资源导致整批泄漏。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface ResourceRegistry {

    /**
     * 登记资源。
     *
     * @param resource 资源
     * @param <T>      资源类型
     * @return 原资源（便于链式使用）
     */
    <T extends AutoCloseable> T register(T resource);

    /**
     * 注销资源（不再由框架回收）。
     *
     * @param resource 资源
     */
    void unregister(AutoCloseable resource);
}
