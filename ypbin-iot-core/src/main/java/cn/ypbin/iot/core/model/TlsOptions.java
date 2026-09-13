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
 * TLS / DTLS 参数。
 *
 * @param enabled           是否启用
 * @param protocol          协议版本，如 TLSv1.3 / DTLSv1.2
 * @param keystoreRef       密钥库引用
 * @param truststoreRef     信任库引用
 * @param insecureSkipVerify 是否跳过对端校验（仅调试用，启用时必须打 warn 日志）
 * @param cipherSuites      密码套件，逗号分隔
 * @author wenbin
 * @since 2026-09-13
 */
public record TlsOptions(
        boolean enabled,
        String protocol,
        String keystoreRef,
        String truststoreRef,
        boolean insecureSkipVerify,
        String cipherSuites) {

    /** 默认 TLS 协议版本。 */
    public static final String DEFAULT_PROTOCOL = "TLSv1.3";

    /**
     * 紧凑构造器：归一化可空字段。
     */
    public TlsOptions {
        protocol = protocol == null || protocol.isEmpty() ? DEFAULT_PROTOCOL : protocol;
        keystoreRef = keystoreRef == null ? "" : keystoreRef;
        truststoreRef = truststoreRef == null ? "" : truststoreRef;
        cipherSuites = cipherSuites == null ? "" : cipherSuites;
    }

    /**
     * 明文传输。
     *
     * @return 未启用 TLS 的参数
     */
    public static TlsOptions disabled() {
        return new TlsOptions(false, DEFAULT_PROTOCOL, "", "", false, "");
    }

    /**
     * 启用 TLS（使用默认协议版本）。
     *
     * @return 启用 TLS 的参数
     */
    public static TlsOptions enabledDefault() {
        return new TlsOptions(true, DEFAULT_PROTOCOL, "", "", false, "");
    }
}
