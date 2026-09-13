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

import cn.ypbin.iot.core.context.CredentialResolver;
import java.util.Map;
import java.util.Optional;

/**
 * 基于环境变量的凭据解析器。
 *
 * <p>引用格式：{@code env:VAR_NAME} 或 {@code env:VAR_NAME:用户名}。
 * 明文密码<b>不入库</b>，只通过环境变量注入，符合凭据零入库约束。</p>
 *
 * <p>未识别的引用前缀返回空 Optional（由调用方决定拒绝连接还是匿名连接），
 * 并<b>不</b>尝试任何猜测性回退。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class EnvCredentialResolver implements CredentialResolver {

    /** 环境变量引用前缀。 */
    public static final String PREFIX = "env:";

    private static final String SEPARATOR = ":";

    @Override
    public Optional<Credential> resolve(String ref) {
        if (ref == null || !ref.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String body = ref.substring(PREFIX.length());
        if (body.isBlank()) {
            return Optional.empty();
        }
        String variable = body;
        String username = "";
        int index = body.indexOf(SEPARATOR);
        if (index >= 0) {
            variable = body.substring(0, index);
            username = body.substring(index + 1);
        }
        String secret = System.getenv(variable);
        if (secret == null) {
            return Optional.empty();
        }
        return Optional.of(new Credential(username, secret.toCharArray(), Map.of()));
    }
}
