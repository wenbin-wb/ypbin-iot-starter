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
package cn.ypbin.iot.core.spi;

import cn.ypbin.iot.core.model.DeviceSpec;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按设备分配 {@link DeviceChange#revision()} 的原子递增分配器（issue #38）。
 *
 * <p><b>为什么框架要提供它</b>：{@code revision} 是「设备会不会有数据」的隐式契约——
 * 宿主漏了按设备递增 ⇒ 变更被判为过期而<b>静默丢弃</b>（设备永久零数据）。这条契约与业务语义无关
 * （框架不需要知道设备是什么），因此不该由每个宿主各自手写 {@code AtomicLong}。
 * 宿主改成 {@code allocator.dispatch(type, device)} 后不再需要维护任何计数器。</p>
 *
 * <p><b>兼容宿主已有的自增实现</b>：{@link #dispatch(ChangeType, DeviceSpec, long)} 与
 * {@link #nextRevision(String, long)} 取 {@code max(分配值, 宿主传入值)}——
 * 宿主若已经在用自己的单调计数器（例如数据库时间戳、租约版本号），传入更大的值仍然生效，
 * 不会被框架的分配器「压回去」。</p>
 *
 * <p><b>单调性</b>：同一 {@code deviceId} 的分配只会变大，永不复用旧值；
 * 不同设备互不影响（各自一个 {@code AtomicLong}）。分配器只保证「分配」侧的单调，
 * <b>不做去重</b>——去重仍由 {@code IotLifecycle} 按设备记录最后已应用的 revision 完成
 * （同一条变更被重复投递时携带的是同一个 revision，因此仍会被正确丢弃）。</p>
 *
 * <p><b>内存</b>：每台设备一个 {@code AtomicLong}，设备被移除时调用 {@link #forget(String)}
 * 释放（{@code IotLifecycle} 在 {@code REMOVE} 路径上调用）。</p>
 *
 * <p><b>无 Spring 依赖</b>：本类位于 {@code iot-core}，可脱离容器使用；
 * starter 会把它装配为 {@code @ConditionalOnMissingBean} 的 Bean 供宿主注入。</p>
 *
 * <p>线程安全：全部方法可并发调用。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
public final class DeviceRevisionAllocator {

    /** 每台设备的当前 revision。 */
    private final ConcurrentMap<String, AtomicLong> revisions = new ConcurrentHashMap<>();

    /**
     * 为设备分配下一个 revision（严格递增，从 1 开始）。
     *
     * @param deviceId 设备标识
     * @return 新的 revision
     */
    public long nextRevision(String deviceId) {
        return counterOf(deviceId).updateAndGet(previous -> previous + 1L);
    }

    /**
     * 为设备分配修订号，并兼容宿主传入的更大值（取 {@code max(分配值, 传入值)}）。
     *
     * <p>宿主传入的更大值也会被记为当前值，后续分配从它继续递增（不会回退）。</p>
     *
     * @param deviceId     设备标识
     * @param hostRevision 宿主自行计算的 revision；可传 {@code 0} 表示「不参与比较」
     * @return 生效的 revision
     */
    public long nextRevision(String deviceId, long hostRevision) {
        return counterOf(deviceId).updateAndGet(previous -> Math.max(previous + 1L, hostRevision));
    }

    /**
     * 构造一条由框架分配 revision 的设备变更。
     *
     * @param type   变更类型
     * @param device 设备规格
     * @return 携带框架分配 revision 的变更
     */
    public DeviceChange dispatch(ChangeType type, DeviceSpec device) {
        Objects.requireNonNull(device, "device must not be null");
        return new DeviceChange(type, device, nextRevision(device.deviceId()));
    }

    /**
     * 构造一条设备变更，revision 取 {@code max(框架分配值, 宿主传入值)}。
     *
     * @param type         变更类型
     * @param device       设备规格
     * @param hostRevision 宿主自行计算的 revision
     * @return 携带生效 revision 的变更
     */
    public DeviceChange dispatch(ChangeType type, DeviceSpec device, long hostRevision) {
        Objects.requireNonNull(device, "device must not be null");
        return new DeviceChange(type, device, nextRevision(device.deviceId(), hostRevision));
    }

    /**
     * 当前已分配到的 revision（未分配过返回 {@code 0}）。
     *
     * @param deviceId 设备标识
     * @return 当前 revision
     */
    public long currentRevision(String deviceId) {
        AtomicLong counter = revisions.get(deviceId);
        return counter == null ? 0L : counter.get();
    }

    /**
     * 忘记一台设备（设备被移除时调用，避免分配表随设备增删无限增长）。
     *
     * @param deviceId 设备标识
     */
    public void forget(String deviceId) {
        revisions.remove(deviceId);
    }

    /**
     * 当前跟踪的设备数（诊断用）。
     *
     * @return 设备数
     */
    public int trackedDevices() {
        return revisions.size();
    }

    private AtomicLong counterOf(String deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        return revisions.computeIfAbsent(deviceId, ignored -> new AtomicLong());
    }
}
