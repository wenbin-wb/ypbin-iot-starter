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
package cn.ypbin.iot.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置元数据门禁。
 *
 * <p>{@code spring-boot-configuration-processor} 缺失或未生效时<b>不会报错</b>，
 * 后果是宿主 IDE 对所有 {@code ypbin.iot.*} 配置项<b>没有任何补全与提示</b>——
 * 「配置了不生效」的另一种形态，而且极难被察觉。本门禁断言：</p>
 *
 * <ul>
 *   <li>构建产物里确实有 {@code spring-configuration-metadata.json}；</li>
 *   <li>每个 {@code @ConfigurationProperties} 前缀在元数据里都有对应的属性（不只是空文件）。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-14
 */
class ConfigMetadataTest {

    private static final String METADATA_RESOURCE = "META-INF/spring-configuration-metadata.json";

    private static String metadata;

    @BeforeAll
    static void loadMetadata() throws Exception {
        try (InputStream input = ConfigMetadataTest.class.getClassLoader()
                .getResourceAsStream(METADATA_RESOURCE)) {
            assertThat(input)
                    .as("类路径上没有 %s —— configuration-processor 未生效，"
                            + "宿主的 IDE 将没有任何配置补全（不会报错，只是静默失效）", METADATA_RESOURCE)
                    .isNotNull();
            metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("CFGMETA-01 元数据必须包含项目自有前缀的属性（不能是空文件）")
    void metadataMustContainOwnProperties() {
        assertThat(metadata)
                .as("元数据里没有任何 ypbin.iot 前缀的属性 —— 处理器没跑到本项目的配置类上")
                .contains("ypbin.iot");
    }

    @Test
    @DisplayName("CFGMETA-02 每个 @ConfigurationProperties 前缀都必须在元数据里出现")
    void everyConfigurationPropertiesPrefixMustAppear() {
        List<String> prefixes = List.of(
                IotPropertiesPrefixes.ROOT,
                IotPropertiesPrefixes.CONNECTION,
                IotPropertiesPrefixes.EGRESS,
                IotPropertiesPrefixes.SCHEDULER,
                IotPropertiesPrefixes.DEVICES);

        for (String prefix : prefixes) {
            assertThat(metadata)
                    .as("@ConfigurationProperties 前缀 %s 在元数据里缺失 —— "
                            + "该前缀下的配置项在宿主 IDE 里不会有任何提示", prefix)
                    .contains(prefix);
        }
    }

    @Test
    @DisplayName("CFGMETA-04 每个带 @ConfigurationProperties 的模块都必须产出元数据")
    void everyModuleWithConfigurationPropertiesMustProduceMetadata() {
        // 这条是本门禁真正防住的回归：协议模块曾**只有注解、没有配置处理器**，
        // 于是 ypbin.iot.protocol.* 的配置项在宿主 IDE 里完全没有补全
        // （不报错、不影响运行，只是静默失去提示）。
        List<String> modules = List.of(
                "ypbin-iot-spring-boot-starter",
                "ypbin-iot-protocol-tcp",
                "ypbin-iot-protocol-modbus",
                "ypbin-iot-protocol-mqtt",
                "ypbin-iot-protocol-opcua");
        List<String> missing = new ArrayList<>();
        for (String module : modules) {
            boolean found = false;
            try {
                Enumeration<URL> resources = ConfigMetadataTest.class.getClassLoader()
                        .getResources(METADATA_RESOURCE);
                while (resources.hasMoreElements()) {
                    if (resources.nextElement().toString().contains(module)) {
                        found = true;
                        break;
                    }
                }
            } catch (IOException ex) {
                throw new IllegalStateException("无法枚举配置元数据资源", ex);
            }
            if (!found) {
                missing.add(module);
            }
        }
        assertThat(missing)
                .as("以下模块没有产出 spring-configuration-metadata.json —— "
                        + "通常是漏了 spring-boot-configuration-processor 依赖，"
                        + "宿主 IDE 会静默失去这些配置项的补全")
                .isEmpty();
    }

    @Test
    @DisplayName("CFGMETA-03 自检：前缀清单必须与配置类实际声明一致（防止清单过期后误放行）")
    void prefixListMustMatchDeclaredAnnotation() {
        ConfigurationProperties annotation = IotPropertiesPrefixes.TYPE
                .getAnnotation(ConfigurationProperties.class);
        assertThat(annotation)
                .as("自检样本必须真的带 @ConfigurationProperties，否则本门禁会静默失效")
                .isNotNull();
        assertThat(annotation.prefix())
                .as("配置类的前缀变了但门禁清单没跟着变")
                .isEqualTo(IotPropertiesPrefixes.ROOT);
    }

    @Test
    @DisplayName("CFGMETA-05 自检：全部协议模块都必须在 arch-tests 的 classpath 上")
    void allProtocolModulesMustBeOnClasspath() {
        // ArchUnit 规则用 importPackages("cn.ypbin.iot")：**不在 classpath 上的模块会被静默跳过**。
        // 本仓曾只依赖 protocol-tcp，于是 modbus/mqtt/opcua 的字节码规则（如
        // 「协议实现包不得使用 Spring 类型」）看起来通过、实则从未检查过它们。
        // 这条自检把「规则覆盖了哪些模块」变成可断言的事实。
        List<String> protocolPackages = List.of(
                "cn/ypbin/iot/protocol/tcp",
                "cn/ypbin/iot/protocol/modbus",
                "cn/ypbin/iot/protocol/mqtt",
                "cn/ypbin/iot/protocol/opcua");
        List<String> missing = new ArrayList<>();
        for (String pkg : protocolPackages) {
            if (ConfigMetadataTest.class.getClassLoader().getResource(pkg) == null) {
                missing.add(pkg);
            }
        }
        assertThat(missing)
                .as("以下协议模块不在 arch-tests 的 classpath 上 —— 架构规则会静默跳过它们；"
                        + "请在 ypbin-iot-architecture-tests/pom.xml 中补上依赖")
                .isEmpty();
    }

    /**
     * 配置前缀清单：与 {@code IotProperties} 的实际声明保持一致。
     *
     * <p>刻意在这里<b>手写清单</b>而不是反射扫描全部属性——目的是让「新增前缀必须显式登记」
     * 成为一个需要人动手的动作，而不是自动跟随（自动跟随会让门禁失去发现新增项的能力）。</p>
     *
     * @author wenbin
     * @since 2026-09-14
     */
    static final class IotPropertiesPrefixes {

        /** 根前缀。 */
        static final String ROOT = "ypbin.iot";

        static final String CONNECTION = "ypbin.iot.connection";

        static final String EGRESS = "ypbin.iot.egress";

        static final String SCHEDULER = "ypbin.iot.scheduler";

        static final String DEVICES = "ypbin.iot.devices";

        /** 配置类类型（自检用）。 */
        static final Class<?> TYPE = resolveType();

        private IotPropertiesPrefixes() {
        }

        private static Class<?> resolveType() {
            try {
                return Class.forName("cn.ypbin.iot.spring.autoconfigure.IotProperties");
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("配置类不存在，门禁的自检样本失效", ex);
            }
        }
    }
}
