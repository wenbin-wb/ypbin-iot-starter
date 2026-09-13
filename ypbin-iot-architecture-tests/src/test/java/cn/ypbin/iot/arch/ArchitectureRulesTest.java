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

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 架构约束测试：对<b>主源码</b>执行全部规则，要求通过。
 *
 * <p>这些规则把设计文档中的承诺变成构建失败：
 * 「契约层零 Spring/零 Netty」「协议实现包零 Spring」「依赖方向不可逆」
 * 「禁 synchronized / printStackTrace / System.out / Collections.emptyXxx」。</p>
 *
 * <p>规则本身的正确性由 {@link RuleEffectivenessTest} 用故意违规的夹具反向验证——
 * 因为 ArchUnit 规则很容易写成恒为真而永不报错（母仓已踩过三次）。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class ArchitectureRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importMainClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("cn.ypbin.iot");
    }

    @Test
    @DisplayName("ARCH-01 契约层/运行时内核/传输底座必须零 Spring 依赖")
    void layersMustBeSpringFree() {
        for (ArchRule rule : ArchitectureRules.springFreeLayers()) {
            rule.check(classes);
        }
    }

    @Test
    @DisplayName("ARCH-02 iot-core 不得依赖 Netty")
    void coreMustNotDependOnNetty() {
        for (ArchRule rule : ArchitectureRules.coreMustNotDependOnNetty()) {
            rule.check(classes);
        }
    }

    @Test
    @DisplayName("ARCH-03 协议实现包不得使用 Spring 类型")
    void protocolPackageMustBeSpringFree() {
        for (ArchRule rule : ArchitectureRules.protocolPackageMustBeSpringFree()) {
            rule.check(classes);
        }
    }

    @Test
    @DisplayName("ARCH-04 依赖方向不可逆")
    void layeringMustNotBeInverted() {
        for (ArchRule rule : ArchitectureRules.layeringMustNotBeInverted()) {
            rule.check(classes);
        }
    }

    @Test
    @DisplayName("ARCH-05 编码铁律（synchronized / printStackTrace / System.out / Collections.emptyXxx）")
    void codingRulesMustHold() {
        for (ArchRule rule : ArchitectureRules.codingRules()) {
            rule.check(classes);
        }
    }
}
