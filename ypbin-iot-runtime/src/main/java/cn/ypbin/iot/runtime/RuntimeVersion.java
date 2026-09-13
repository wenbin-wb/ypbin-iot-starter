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
package cn.ypbin.iot.runtime;

/**
 * 运行时版本。
 *
 * <p>协议适配器在 {@code ProtocolDescriptor} 中声明所支持的版本区间，注册时校验；
 * 不在区间内即终止启动，把「不兼容组合」从运行期的疑难异常变成启动期的明确报错。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class RuntimeVersion {

    /** 当前 iot-runtime 版本。 */
    public static final String VERSION = "0.1.0";

    private RuntimeVersion() {
    }
}
