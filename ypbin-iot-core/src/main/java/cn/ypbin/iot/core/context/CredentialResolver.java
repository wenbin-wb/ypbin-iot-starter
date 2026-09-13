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

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * 凭据解析：把 {@code ConnectionSpec.credentialRef} 解析为运行时凭据。
 *
 * <p>凭据以 {@code char[]} 承载而非 {@code String}，便于使用后清零；
 * 实现方<b>不得</b>把凭据写入日志或异常消息。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface CredentialResolver {

    /**
     * 解析凭据引用。
     *
     * @param ref 凭据引用（如 {@code env:IOT_DEVICE_PWD}）
     * @return 凭据；引用不存在时返回空 Optional，由调用方决定拒绝连接还是匿名连接
     */
    Optional<Credential> resolve(String ref);

    /**
     * 运行时凭据。
     *
     * @param username   用户名
     * @param secret     密钥（便于清零）
     * @param attributes 附加属性
     * @author wenbin
     * @since 2026-09-13
     */
    record Credential(String username, char[] secret, Map<String, String> attributes) {

        /**
         * 紧凑构造器：做防御性拷贝并归一带空字段。
         */
        public Credential {
            username = username == null ? "" : username;
            secret = secret == null ? new char[0] : Arrays.copyOf(secret, secret.length);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        /** 清零凭据内容。 */
        public void wipe() {
            Arrays.fill(secret, '\0');
        }
    }
}
