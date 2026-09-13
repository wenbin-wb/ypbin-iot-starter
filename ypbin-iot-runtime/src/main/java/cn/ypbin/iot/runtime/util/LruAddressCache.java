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
package cn.ypbin.iot.runtime.util;

import cn.ypbin.iot.core.context.BoundedAddressCache;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 有界地址解析缓存：LRU 淘汰 + 锁保护。
 *
 * <p>为什么不用 {@code synchronized}：虚拟线程在 {@code synchronized} 块内阻塞会 pinning
 * 到载体线程，本仓统一使用 {@link ReentrantLock}。</p>
 *
 * <p>为什么不用 {@code Caffeine}：core/runtime 保持零第三方依赖（除 SLF4J），
 * 且这里的负载特征（有限 key、只读为主、淘汰不敏感）不值得引入缓存框架。</p>
 *
 * @param <A> 解析后的地址类型
 * @author wenbin
 * @since 2026-09-13
 */
public final class LruAddressCache<A> implements BoundedAddressCache<A> {

    private final int maximumSize;

    private final Map<String, A> cache;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 创建有界缓存。
     *
     * @param maximumSize 最大条目数，必须为正
     */
    public LruAddressCache(int maximumSize) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximumSize must be positive: " + maximumSize);
        }
        this.maximumSize = maximumSize;
        this.cache = new LinkedHashMap<>(Math.min(maximumSize, 1024), 0.75F, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, A> eldest) {
                return size() > LruAddressCache.this.maximumSize;
            }
        };
    }

    @Override
    public A get(String raw, Function<String, A> loader) {
        Objects.requireNonNull(raw, "raw must not be null");
        Objects.requireNonNull(loader, "loader must not be null");
        A cached = read(raw);
        if (cached != null) {
            return cached;
        }
        A parsed = loader.apply(raw);
        Objects.requireNonNull(parsed, "loader must not return null for address: " + raw);
        write(raw, parsed);
        return parsed;
    }

    @Override
    public void invalidateAll() {
        lock.lock();
        try {
            cache.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }

    private A read(String raw) {
        lock.lock();
        try {
            return cache.get(raw);
        } finally {
            lock.unlock();
        }
    }

    private void write(String raw, A value) {
        lock.lock();
        try {
            cache.put(raw, value);
        } finally {
            lock.unlock();
        }
    }
}
