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
package cn.ypbin.iot.spring.autoconfigure;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * IoT 接入默认值注入。
 *
 * <p>把本仓的 i18n 资源 basename <b>追加</b>到 {@code spring.messages.basename}，
 * 使各协议模块自带的 {@code messages_zh_CN.properties} / {@code messages_en_US.properties}
 * 能被容器的 {@code MessageSource} 解析——复用母仓 {@code ypbin-starter-i18n} 的
 * {@code I18nUtil}，不自造第二套 i18n 机制。</p>
 *
 * <p><b>Boot 4.1 注册键陷阱</b>：本类实现的是 {@code org.springframework.boot.EnvironmentPostProcessor}
 * （Boot 4.1 已从 {@code org.springframework.boot.env.*} 迁移），
 * {@code META-INF/spring.factories} 的注册键<b>必须</b>与之同步。
 * 只改接口不改键不会报错，但默认值会静默失效。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public class IotDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 默认值属性源名称。 */
    public static final String PROPERTY_SOURCE_NAME = "ypbin-iot-defaults";

    /** 本仓消息资源 basename。 */
    public static final String MESSAGES_BASENAME = "ypbin-iot-messages";

    /** 消息 basename 配置键。 */
    public static final String MESSAGES_BASENAME_KEY = "spring.messages.basename";

    /** 默认值属性源优先级：低于用户配置，高于框架默认。 */
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put(MESSAGES_BASENAME_KEY, mergedBasename(environment.getProperty(MESSAGES_BASENAME_KEY)));
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 把本仓 basename 追加到已有的 basename 列表，保持用户配置优先。
     *
     * @param existing 现有配置值；可能为空
     * @return 合并后的逗号分隔 basename
     */
    private static String mergedBasename(String existing) {
        Set<String> basenames = new LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            for (String item : existing.split(",")) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    basenames.add(trimmed);
                }
            }
        }
        basenames.add(MESSAGES_BASENAME);
        return String.join(",", basenames);
    }
}
