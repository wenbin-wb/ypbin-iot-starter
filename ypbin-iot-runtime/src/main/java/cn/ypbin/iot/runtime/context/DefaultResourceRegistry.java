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
package cn.ypbin.iot.runtime.context;

import cn.ypbin.iot.core.context.ResourceRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 适配器级资源登记处默认实现。
 *
 * <p>关闭时按登记的<b>逆序</b>释放；单个资源释放失败只记录日志并继续释放其余资源，
 * 避免一个坏资源导致整批泄漏。</p>
 *
 * <p>线程安全：内部用 {@link ReentrantLock} 保护，
 * 不使用 {@code synchronized}（虚拟线程下会 pinning）。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class DefaultResourceRegistry implements ResourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultResourceRegistry.class);

    private final Deque<AutoCloseable> resources = new ArrayDeque<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public <T extends AutoCloseable> T register(T resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        lock.lock();
        try {
            if (closed.get()) {
                // 已关闭还注册会 push 进一个再无人 drain 的队列，等于永久泄漏：
                // 立即关闭并显式报错，而不是静默接收
                throw new IllegalStateException("resource registry already closed: "
                        + resource.getClass().getName());
            }
            resources.push(resource);
            return resource;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void unregister(AutoCloseable resource) {
        lock.lock();
        try {
            // 按引用移除：Deque.remove(Object) 用 equals 语义，资源实现若按值相等会移错实例
            resources.removeIf(candidate -> candidate == resource);
        } finally {
            lock.unlock();
        }
    }

    /** 逆序释放全部资源；幂等。关闭后再 register 会抛 {@link IllegalStateException}。 */
    public void close() {
        closed.set(true);
        while (true) {
            AutoCloseable resource;
            lock.lock();
            try {
                resource = resources.poll();
            } finally {
                lock.unlock();
            }
            if (resource == null) {
                return;
            }
            try {
                resource.close();
            } catch (Exception ex) {
                log.error("[ypbin-iot] failed to close resource {}", resource.getClass().getName(), ex);
            }
        }
    }
}
