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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 架构规则<b>有效性自检</b>：对故意违规的测试夹具执行规则，断言规则确实会报错。
 *
 * <p><b>为什么必须有这个测试类</b>：ArchUnit 规则写错时不会报错，而是<b>静默放行</b>——
 * 例如把包名写错、把匹配条件写成永不命中的表达式，规则就变成了一句装饰。
 * 母仓已因此踩过三个坑（{@code callMethod} 的 owner 是子类、
 * {@code switch(enum)} 被编译成 {@code ordinal()} 查表、Lombok {@code @Data} 字节码不可见），
 * 并总结出「规则必须配合成违规样本反向验证」的做法。</p>
 *
 * <p>本类与 {@link ArchitectureRulesTest} 共享同一份规则定义
 * （{@link ArchitectureRules}），一个断言「主源码通过」，一个断言「夹具失败」。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class RuleEffectivenessTest {

    private static JavaClasses fixtureClasses;

    @BeforeAll
    static void importFixtures() {
        fixtureClasses = new ClassFileImporter()
                .importPackages("cn.ypbin.iot.arch.fixture", "cn.ypbin.iot.core.fixture");
    }

    @Test
    @DisplayName("SELF-01 夹具确实被加载（否则下面的断言会因空集合而假通过）")
    void fixturesMustBeLoaded() {
        assertThat(fixtureClasses)
                .as("故意违规的夹具必须真的被导入，否则本类所有断言都是空的")
                .isNotEmpty();
    }

    @Test
    @DisplayName("SELF-02 零 Spring 规则必须能捕捉 core 包下的 Spring 依赖")
    void springFreeRuleMustDetectViolation() {
        assertRuleFails(ArchitectureRules.springFreeLayers().get(0));
    }

    @Test
    @DisplayName("SELF-03 synchronized 规则必须能捕捉违规")
    void synchronizedRuleMustDetectViolation() {
        assertRuleFails(ArchitectureRules.codingRules().get(0));
    }

    @Test
    @DisplayName("SELF-04 printStackTrace 规则必须能捕捉违规（母仓陷阱：不能按 owner 匹配）")
    void printStackTraceRuleMustDetectViolation() {
        assertRuleFails(ArchitectureRules.codingRules().get(1));
    }

    @Test
    @DisplayName("SELF-05 System.out 规则必须能捕捉违规")
    void systemOutRuleMustDetectViolation() {
        assertRuleFails(ArchitectureRules.codingRules().get(2));
    }

    @Test
    @DisplayName("SELF-06 Collections.emptyList 规则必须能捕捉违规")
    void collectionsEmptyListRuleMustDetectViolation() {
        assertRuleFails(ArchitectureRules.codingRules().get(3));
    }

    private static void assertRuleFails(ArchRule rule) {
        assertThatThrownBy(() -> rule.check(fixtureClasses))
                .as("规则 [%s] 未能捕捉故意违规的夹具 —— 该规则可能写成了恒为真，形同虚设", rule)
                .isInstanceOf(AssertionError.class);
    }
}
