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
package cn.ypbin.iot.core.fixture;

import org.springframework.context.annotation.Configuration;

/**
 * <b>故意违规的测试夹具</b>：位于 {@code cn.ypbin.iot.core..} 包下却 import 了 Spring 类型。
 *
 * <p>它<b>只存在于测试源码</b>，用于验证「core 层零 Spring」这条架构规则真的能报错。
 * 若规则写错（例如包匹配写成了永远不命中的表达式），本夹具不会被捕捉，
 * {@code RuleEffectivenessTest} 就会失败——这正是母仓总结的「规则有效性自检」。</p>
 *
 * <p>⚠️ <b>2026-09-18 修正</b>：本类的 {@code package} 原写作 {@code cn.ypbin.iot.arch.fixture}
 * （与所在目录 {@code cn/ypbin/iot/core/fixture/} 不一致），于是它<b>不在</b>规则目标包
 * {@code cn.ypbin.iot.core..} 内 ⇒ 规则的选择集为空，而 ArchUnit 默认 {@code failOnEmptyShould=true}
 * 会因「没有类可检查」抛 AssertionError ⇒ 自检<b>因空集而通过</b>（假绿）。
 * 已把包声明改正，并让断言要求报错正文出现夹具类名（见 {@code assertRuleFails}）。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
@Configuration
public class SpringInCoreLayerFixture {
}
