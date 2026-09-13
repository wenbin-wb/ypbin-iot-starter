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
package cn.ypbin.iot.arch.fixture;

import java.util.Collections;
import java.util.List;

/**
 * <b>故意违规的测试夹具</b>：集中放置编码铁律禁止的写法，用于验证规则有效性。
 *
 * <p>违规项：{@code synchronized} 方法、{@code printStackTrace()}、{@code System.out}、
 * {@code Collections.emptyList()}。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public class UnsafeCodingFixture {

    /**
     * 违规：自研代码禁止 synchronized 方法（虚拟线程下会 pinning）。
     */
    public synchronized void synchronizedMethod() {
        // 违规示例
    }

    /**
     * 违规：禁止 printStackTrace。
     *
     * @param throwable 异常
     */
    public void printStackTrace(Throwable throwable) {
        throwable.printStackTrace();
    }

    /**
     * 违规：禁止 System.out 输出。
     *
     * @param message 消息
     */
    public void print(String message) {
        System.out.println(message);
    }

    /**
     * 违规：字面量空集合必须用 {@code List.of()}，禁用 {@code Collections.emptyList()}。
     *
     * @return 空列表
     */
    public List<String> emptyList() {
        return Collections.emptyList();
    }
}
