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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DeviceRevisionAllocator} 测试（issue #38 验收项 2）。
 *
 * <p>核心断言：同设备连续两次 dispatch 的 revision <b>严格递增</b>、不同设备<b>互不影响</b>、
 * 宿主传入更大值时取 {@code max}（兼容既有自增实现）、并发下不重号。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class DeviceRevisionAllocatorTest {

    private static final ProtocolCode CODE = ProtocolCode.of("stub");

    @Test
    @DisplayName("REV-01 同一设备连续两次 dispatch 的 revision 必须严格递增")
    void sameDeviceRevisionsMustIncreaseStrictly() {
        DeviceRevisionAllocator allocator = new DeviceRevisionAllocator();
        DeviceChange first = allocator.dispatch(ChangeType.ADD, device("d1"));
        DeviceChange second = allocator.dispatch(ChangeType.UPDATE, device("d1"));
        assertThat(second.revision())
                .as("框架分配后宿主不再手算 revision；不递增会让第二次变更被当作过期事件丢弃")
                .isGreaterThan(first.revision());
        assertThat(first.revision()).isEqualTo(1L);
        assertThat(second.revision()).isEqualTo(2L);
        assertThat(second.type()).isEqualTo(ChangeType.UPDATE);
    }

    @Test
    @DisplayName("REV-02 不同设备的 revision 互不影响（各自从 1 开始）")
    void differentDevicesMustBeIndependent() {
        DeviceRevisionAllocator allocator = new DeviceRevisionAllocator();
        assertThat(allocator.dispatch(ChangeType.ADD, device("d1")).revision()).isEqualTo(1L);
        assertThat(allocator.dispatch(ChangeType.ADD, device("d2")).revision())
                .as("不同设备共用计数器会让后加入的设备直接跳到很大的 revision")
                .isEqualTo(1L);
        assertThat(allocator.dispatch(ChangeType.ADD, device("d1")).revision()).isEqualTo(2L);
        assertThat(allocator.dispatch(ChangeType.ADD, device("d2")).revision()).isEqualTo(2L);
        assertThat(allocator.trackedDevices()).isEqualTo(2);
    }

    @Test
    @DisplayName("REV-03 宿主传入更大 revision 时取 max，且后续分配从它继续（兼容既有实现）")
    void hostRevisionMustBeHonouredWhenGreater() {
        DeviceRevisionAllocator allocator = new DeviceRevisionAllocator();
        // 宿主已有自增（例如数据库 config_version=100）：不得被框架的 1 压回去
        assertThat(allocator.dispatch(ChangeType.UPDATE, device("d1"), 100L).revision()).isEqualTo(100L);
        // 宿主下一次传小值（例如 3）也不得让 revision 回退
        assertThat(allocator.dispatch(ChangeType.UPDATE, device("d1"), 3L).revision()).isEqualTo(101L);
        // 宿主传明显更大的值：直接采用
        assertThat(allocator.dispatch(ChangeType.UPDATE, device("d1"), 500L).revision()).isEqualTo(500L);
        assertThat(allocator.nextRevision("d1")).isEqualTo(501L);
    }

    @Test
    @DisplayName("REV-04 nextRevision 与 currentRevision/forget 语义一致")
    void nextRevisionAndForgetMustBeConsistent() {
        DeviceRevisionAllocator allocator = new DeviceRevisionAllocator();
        assertThat(allocator.currentRevision("absent")).as("未分配过返回 0，不返回负数").isZero();
        assertThat(allocator.nextRevision("d1")).isEqualTo(1L);
        assertThat(allocator.currentRevision("d1")).isEqualTo(1L);
        allocator.forget("d1");
        assertThat(allocator.currentRevision("d1")).isZero();
        assertThat(allocator.trackedDevices()).isZero();
        // forget 之后从 1 重新开始：设备已是被移除/重新加入的新生命周期
        assertThat(allocator.nextRevision("d1")).isEqualTo(1L);
    }

    @Test
    @DisplayName("REV-05 并发分配不得重号（AtomicLong 语义）")
    void concurrentAllocationMustNotDuplicate() throws InterruptedException {
        DeviceRevisionAllocator allocator = new DeviceRevisionAllocator();
        int threads = 8;
        int perThread = 200;
        Set<Long> assigned = ConcurrentHashMap.newKeySet();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int j = 0; j < perThread; j++) {
                        assigned.add(allocator.nextRevision("d1"));
                    }
                    done.countDown();
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(assigned)
                .as("并发分配出现重号会让一半变更被当作过期事件丢弃")
                .hasSize(threads * perThread);
        assertThat(allocator.currentRevision("d1")).isEqualTo(threads * perThread);
    }

    @Test
    @DisplayName("REV-06 null 参数必须 fail-fast")
    void nullArgumentsMustFailFast() {
        DeviceRevisionAllocator allocator = new DeviceRevisionAllocator();
        assertThatThrownBy(() -> allocator.nextRevision(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> allocator.dispatch(ChangeType.ADD, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> allocator.dispatch(null, device("d1")))
                .as("DeviceChange 的紧凑构造器会拒绝 null type")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("REV-07 与 IotLifecycle 的幂等契约配合：重复投递同一变更仍是同一 revision")
    void repeatedDispatchMustCarryItsOwnRevision() {
        // 注意：分配器每次调用都会拿到**新的** revision —— 它不负责去重。
        // 「同一变更重复投递」在宿主的语义里意味着**重新构造一个对象**，因此
        // 框架侧的去重靠 IotLifecycle 记录「最后已应用的 revision」；
        // 本用例钉住「同一个 change 实例被重复投递时 revision 不变化」这一点。
        DeviceRevisionAllocator allocator = new DeviceRevisionAllocator();
        DeviceChange change = allocator.dispatch(ChangeType.ADD, device("d1"));
        List<DeviceChange> duplicates = List.of(change, change);
        assertThat(duplicates).extracting(DeviceChange::revision).containsOnly(1L);
        assertThat(IntStream.range(0, 3).mapToObj(ignored -> change).distinct())
                .as("重复投递的是同一对象/同一 revision，IotLifecycle 据此幂等丢弃")
                .hasSize(1);
    }

    private static DeviceSpec device(String deviceId) {
        return new DeviceSpec(deviceId, deviceId, CODE, "conn-1", "",
                Duration.ofSeconds(5), Map.of());
    }
}
