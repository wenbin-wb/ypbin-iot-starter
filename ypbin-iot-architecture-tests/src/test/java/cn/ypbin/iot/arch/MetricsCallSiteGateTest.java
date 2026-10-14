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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 协议层错误指标**双向**门禁：{@code docs/METRICS.md} 错误码表 ↔ 协议层 {@code recordError} 调用点。
 *
 * <p>issue #27 的病根是「文档/手册承诺了 {@code iot.tcp.*} 指标，但协议层
 * {@code MetricsRecorder#recordError} 零调用」——指标永远不注册，宿主 {@code /actuator/metrics}
 * 只能看到 404。光有文档会烂，故这里加机器判据：</p>
 *
 * <ul>
 *   <li><b>表 → 代码</b>：表中每个错误码，其声明的 {@code MSG_*} 常量必须出现在<b>同一协议模块</b>
 *       的某个 {@code recordError(...)} 调用语句里（是「有调用点」，不是「有注解」）；</li>
 *   <li><b>代码 → 表</b>：协议模块里每个 {@code recordError(...)} 语句引用的 {@code MSG_*} 常量
 *       都必须登记在表里（新错误码不许不登记）。</li>
 * </ul>
 *
 * <p>按模块而非按全局常量名匹配：{@code MSG_CONNECTION_INACTIVE}/{@code MSG_SESSION_CLOSED}
 * 等名字在四个协议里复用，全局匹配会让「A 协议没接、B 协议接了」被误判为通过。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
class MetricsCallSiteGateTest {

    /** 从 {@code ypbin-iot-architecture-tests} 回到仓库根。 */
    private static final Path REPO_ROOT = Paths.get("..").toAbsolutePath().normalize();

    private static final Path METRICS_DOC = REPO_ROOT.resolve("docs/METRICS.md");

    /** 清单表格行：`| \`iot.tcp.xxx\` | … |`。 */
    private static final Pattern DOC_ROW = Pattern.compile(
            "^\\|\\s*`(iot\\.(tcp|modbus|mqtt|opcua)\\.[a-z0-9._-]+)`\\s*\\|.*");

    /** 常量声明：{@code static final String MSG_X = "iot.tcp.x";}。 */
    private static final Pattern CONST_DECLARATION = Pattern.compile(
            "static\\s+final\\s+String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"(iot\\.(?:tcp|modbus|mqtt|opcua)\\.[a-z0-9._-]+)\"");

    /** 一条 {@code recordError(...)} 调用语句（参数里不含分号）。 */
    private static final Pattern RECORD_ERROR = Pattern.compile("recordError\\s*\\(([^;]*)\\)", Pattern.DOTALL);

    /** 语句里引用的消息键常量名。 */
    private static final Pattern MSG_CONSTANT = Pattern.compile("\\b(MSG_[A-Z0-9_]+)\\b");

    @Test
    @DisplayName("★ 清单里的每个错误码都必须有 recordError 调用点（防『文档承诺、代码没接』）")
    void everyListedErrorCodeMustHaveRecordErrorCallSite() throws IOException {
        Map<String, Set<String>> documented = documentedCodesByModule();
        assertThat(totalCodes(documented))
                .as("docs/METRICS.md 没解析到错误码（表格形态变了？）⇒ 门禁空跑")
                .isGreaterThan(30);

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Set<String>> module : documented.entrySet()) {
            Path main = moduleMain(module.getKey());
            Map<String, String> codeToConstant = declaredConstants(main);
            Set<String> called = calledMsgConstants(main);
            for (String code : module.getValue()) {
                String constant = codeToConstant.get(code);
                if (constant == null) {
                    missing.add(code + " → 模块 " + module.getKey() + " 里找不到 static final 常量声明");
                } else if (!called.contains(constant)) {
                    missing.add(code + " → 常量 " + constant + " 没有出现在 " + module.getKey()
                            + " 的任何 recordError(...) 调用里");
                }
            }
        }
        assertThat(missing)
                .as("文档承诺的错误码在协议层没有 recordError 调用点（issue #27 复发）：%s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 协议层 recordError 用到的常量必须都登记进清单（防新增错误码不登记）")
    void everyRecordedConstantMustBeDocumented() throws IOException {
        Map<String, Set<String>> documented = documentedCodesByModule();
        assertThat(totalCodes(documented)).as("清单没解析到错误码 ⇒ 门禁空跑").isPositive();

        List<String> undocumented = new ArrayList<>();
        int totalCalled = 0;
        for (Map.Entry<String, Set<String>> module : documented.entrySet()) {
            Path main = moduleMain(module.getKey());
            Map<String, String> codeToConstant = declaredConstants(main);
            Set<String> documentedConstants = module.getValue().stream()
                    .map(codeToConstant::get)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> called = calledMsgConstants(main);
            totalCalled += called.size();
            for (String constant : called) {
                if (!documentedConstants.contains(constant)) {
                    undocumented.add(module.getKey() + " → " + constant);
                }
            }
        }
        assertThat(totalCalled).as("协议层没扫到 recordError 语句 ⇒ 门禁空跑").isGreaterThan(20);
        assertThat(undocumented)
                .as("这些错误码在协议层上报了但没登记进 docs/METRICS.md：%s", undocumented)
                .isEmpty();
    }

    @Test
    @DisplayName("SELF-01 解析器能区分「有调用点」与「只有常量声明」（防门禁空转）")
    void matcherMustDistinguishDeclarationFromCallSite() {
        assertThat(calledConstantsIn("static final String MSG_X = \"iot.tcp.x\";"))
                .as("只有声明没有 recordError 时不得算命中").isEmpty();
        assertThat(calledConstantsIn("context.metrics().recordError(TcpAdapter.MSG_X);"))
                .containsExactly("MSG_X");
        assertThat(calledConstantsIn("context.metrics().recordError(a ? A.MSG_L : B.MSG_R);"))
                .containsExactlyInAnyOrder("MSG_L", "MSG_R");
        // 被注释掉的调用不得算命中（否则「临时注释掉 → 门禁红」的自证会假绿）
        assertThat(calledConstantsIn("// context.metrics().recordError(X.MSG_X);"))
                .as("行注释里的 recordError 不得算命中").isEmpty();
        assertThat(calledConstantsIn("/* context.metrics().recordError(X.MSG_X); */"))
                .as("块注释里的 recordError 不得算命中").isEmpty();
    }

    /** 清单按协议前缀分组：模块目录名 → 该模块登记的错误码集合。 */
    private static Map<String, Set<String>> documentedCodesByModule() throws IOException {
        Map<String, Set<String>> byModule = new LinkedHashMap<>();
        for (String line : Files.readAllLines(METRICS_DOC, StandardCharsets.UTF_8)) {
            Matcher row = DOC_ROW.matcher(line.trim());
            if (row.matches()) {
                byModule.computeIfAbsent(moduleName(row.group(2)), ignored -> new LinkedHashSet<>())
                        .add(row.group(1));
            }
        }
        return byModule;
    }

    private static int totalCodes(Map<String, Set<String>> documented) {
        return documented.values().stream().mapToInt(Set::size).sum();
    }

    private static String moduleName(String prefix) {
        return "ypbin-iot-protocol-" + prefix;
    }

    private static Path moduleMain(String module) {
        return REPO_ROOT.resolve(module).resolve("src/main/java");
    }

    /** 模块内错误码 → 声明它的常量名。 */
    private static Map<String, String> declaredConstants(Path main) throws IOException {
        Map<String, String> codeToConstant = new LinkedHashMap<>();
        for (Path file : javaFiles(main)) {
            Matcher declaration = CONST_DECLARATION.matcher(read(file));
            while (declaration.find()) {
                codeToConstant.putIfAbsent(declaration.group(2), declaration.group(1));
            }
        }
        return codeToConstant;
    }

    /** 模块内所有 recordError 语句引用到的 MSG_* 常量。 */
    private static Set<String> calledMsgConstants(Path main) throws IOException {
        Set<String> called = new LinkedHashSet<>();
        for (Path file : javaFiles(main)) {
            called.addAll(calledConstantsIn(read(file)));
        }
        return called;
    }

    /** 从一段源码文本里抽 recordError(...) 语句引用到的 MSG_* 常量（忽略注释）。 */
    private static Set<String> calledConstantsIn(String code) {
        Set<String> called = new LinkedHashSet<>();
        Matcher call = RECORD_ERROR.matcher(stripComments(code));
        while (call.find()) {
            Matcher constant = MSG_CONSTANT.matcher(call.group(1));
            while (constant.find()) {
                called.add(constant.group(1));
            }
        }
        return called;
    }

    /**
     * 去掉行注释与块注释（字符串/字符字面量内的 {@code //}、{@code /*} 不算注释）。
     *
     * <p>必须做：否则把一条 {@code recordError(...)} 用 {@code //} 注释掉，门禁仍会“看见”它而假绿——</p>
     *
     * @param code 源码文本
     * @return 去注释后的文本
     */
    private static String stripComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        boolean inLine = false;
        boolean inBlock = false;
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < code.length(); i++) {
            char current = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
            if (inLine) {
                if (current == '\n') {
                    inLine = false;
                    out.append(current);
                }
                continue;
            }
            if (inBlock) {
                if (current == '*' && next == '/') {
                    inBlock = false;
                    i++;
                } else if (current == '\n') {
                    out.append(current);
                }
                continue;
            }
            if (inString || inChar) {
                out.append(current);
                if (current == '\\' && next != '\0') {
                    out.append(next);
                    i++;
                } else if (inString && current == '"') {
                    inString = false;
                } else if (inChar && current == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (current == '/' && next == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '\'') {
                inChar = true;
            }
            out.append(current);
        }
        return out.toString();
    }

    private static List<Path> javaFiles(Path main) throws IOException {
        assertThat(main).as("协议模块主源码目录不存在：%s", main).isDirectory();
        try (Stream<Path> walk = Files.walk(main)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
