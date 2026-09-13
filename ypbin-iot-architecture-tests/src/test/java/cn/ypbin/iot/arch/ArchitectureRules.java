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

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.conditions.ArchConditions.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Collections;
import java.util.List;

/**
 * 架构规则集合的唯一来源。
 *
 * <p>规则<b>只在这里定义一次</b>，由两个测试类分别消费：</p>
 * <ul>
 *   <li>{@code ArchitectureRulesTest}：对<b>主源码</b>执行，要求全部通过；</li>
 *   <li>{@code RuleEffectivenessTest}：对<b>故意违规的测试夹具</b>执行，要求<b>必须报错</b>。</li>
 * </ul>
 *
 * <p>这样做的理由是母仓总结的教训：ArchUnit 规则很容易写成「恒为真」而永不报错，
 * 单个测试类既断言通过又断言失败会互相干扰，因此拆成两个消费者共享同一份规则定义。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class ArchitectureRules {

    /** 契约层包。 */
    public static final String CORE = "cn.ypbin.iot.core..";

    /** 运行时内核包。 */
    public static final String RUNTIME = "cn.ypbin.iot.runtime..";

    /** 传输底座包。 */
    public static final String TRANSPORT = "cn.ypbin.iot.transport..";

    /** 协议模块包。 */
    public static final String PROTOCOL = "cn.ypbin.iot.protocol..";

    /** Spring 装配层包。 */
    public static final String SPRING_LAYER = "cn.ypbin.iot.spring..";

    private ArchitectureRules() {
    }

    /**
     * 契约层与运行时内核<b>不得依赖 Spring</b>。
     *
     * <p>这是 DESIGN §1.2 G1 与 §3.4 铁律 A1 的直接执行者：{@code iot-core} 一旦引入 Spring，
     * 「可脱离容器复用」这一核心承诺即失效。</p>
     *
     * @return 规则
     */
    public static List<ArchRule> springFreeLayers() {
        return List.of(
                noClasses()
                        .that().resideInAPackage(CORE)
                        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                        .because("iot-core 是契约层，必须零 Spring 依赖（DESIGN §1.2 G1）"),
                noClasses()
                        .that().resideInAPackage(RUNTIME)
                        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                        .because("iot-runtime 是运行时内核，必须零 Spring 依赖（DESIGN §2.1）"),
                noClasses()
                        .that().resideInAPackage(TRANSPORT)
                        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                        .because("iot-transport 是 Netty 底座，必须零 Spring 依赖（DESIGN §2.1）"));
    }

    /**
     * 契约层<b>不得依赖 Netty</b>。
     *
     * <p>并非所有协议都走 Netty（串口、native、协议库自带栈），契约层被 Netty 绑架会限制选型。</p>
     *
     * @return 规则
     */
    public static List<ArchRule> coreMustNotDependOnNetty() {
        return List.of(
                noClasses()
                        .that().resideInAPackage(CORE)
                        .should().dependOnClassesThat().resideInAnyPackage("io.netty..")
                        .because("iot-core 不得依赖 Netty（DESIGN §1.2 G1 / §3.4 A2）"));
    }

    /**
     * 协议模块的 {@code protocol/} 包<b>不得使用 Spring 类型</b>。
     *
     * <p>只有 {@code autoconfigure} 子包可以碰 Spring（DESIGN §2.4 铁律 A4）。
     * 这条规则是「协议实现可脱容器单测」承诺的唯一执行者。</p>
     *
     * @return 规则
     */
    public static List<ArchRule> protocolPackageMustBeSpringFree() {
        return List.of(
                noClasses()
                        .that().resideInAPackage(PROTOCOL)
                        .and().resideOutsideOfPackage("..autoconfigure..")
                        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                        .because("协议实现包必须零 Spring 注解，只有 autoconfigure 子包可碰 Spring（DESIGN §2.4 A4）"));
    }

    /**
     * 装配层<b>不得被下层依赖</b>（依赖方向不可逆）。
     *
     * @return 规则
     */
    public static List<ArchRule> layeringMustNotBeInverted() {
        return List.of(
                noClasses()
                        .that().resideInAPackage(CORE)
                        .should().dependOnClassesThat().resideInAnyPackage(RUNTIME, TRANSPORT, SPRING_LAYER, PROTOCOL)
                        .because("契约层不得依赖任何上层实现（依赖方向不可逆，DESIGN §2.3）"),
                noClasses()
                        .that().resideInAPackage(RUNTIME)
                        .should().dependOnClassesThat().resideInAnyPackage(SPRING_LAYER, PROTOCOL)
                        .because("运行时内核不得依赖 Spring 装配层或协议模块（DESIGN §2.3）"));
    }

    /**
     * 编码铁律：{@code synchronized}、{@code printStackTrace}、{@code System.out}、
     * {@code Collections.emptyXxx}。
     *
     * <p><b>匹配方式刻意避开了母仓踩过的坑</b>：{@code printStackTrace} 不能用
     * {@code callMethod(Throwable.class, "printStackTrace")} 匹配——调用点的 owner 是
     * 子类（如 {@code RuntimeException}），永远匹配不上，规则会恒为真而失效。
     * 这里改为匹配<b>目标方法名</b>。</p>
     *
     * @return 规则
     */
    public static List<ArchRule> codingRules() {
        return List.of(
                methods().that().areDeclaredInClassesThat().resideInAnyPackage(
                                CORE, RUNTIME, TRANSPORT, PROTOCOL, SPRING_LAYER)
                        .should(not(beSynchronized()))
                        .because("虚拟线程在 synchronized 内阻塞会 pinning 到载体线程，自研代码统一用 ReentrantLock"),
                noClasses().that().resideInAnyPackage(CORE, RUNTIME, TRANSPORT, PROTOCOL, SPRING_LAYER)
                        .should().callMethodWhere(target(name("printStackTrace")))
                        .because("禁止 printStackTrace，必须用日志并传完整堆栈（AGENTS R11）"),
                noClasses().that().resideInAnyPackage(CORE, RUNTIME, TRANSPORT, PROTOCOL, SPRING_LAYER)
                        .should().accessField(System.class, "out")
                        .because("禁止 System.out，必须用日志门面（AGENTS R11）"),
                noClasses().that().resideInAnyPackage(CORE, RUNTIME, TRANSPORT, PROTOCOL, SPRING_LAYER)
                        .should().callMethod(Collections.class, "emptyList")
                        .because("字面量空集合必须用 List.of()（AGENTS R12）"),
                noClasses().that().resideInAnyPackage(CORE, RUNTIME, TRANSPORT, PROTOCOL, SPRING_LAYER)
                        .should().callMethod(Collections.class, "emptyMap")
                        .because("字面量空集合必须用 Map.of()（AGENTS R12）"),
                noClasses().that().resideInAnyPackage(CORE, RUNTIME, TRANSPORT, PROTOCOL, SPRING_LAYER)
                        .should().callMethod(Collections.class, "singletonList")
                        .because("字面量集合必须用 List.of()（AGENTS R12）"));
    }

    /**
     * 方法是否被 {@code synchronized} 修饰。
     *
     * @return 条件
     */
    public static ArchCondition<JavaMethod> beSynchronized() {
        return new ArchCondition<>("be synchronized") {
            @Override
            public void check(JavaMethod item, ConditionEvents events) {
                if (item.getModifiers().contains(JavaModifier.SYNCHRONIZED)) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " is synchronized"));
                }
            }
        };
    }
}
