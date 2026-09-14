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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 源码级规范门禁。
 *
 * <p><b>为什么这些规则必须在源码层而不是字节码层</b>（母仓总结的教训）：</p>
 * <ul>
 *   <li><b>内联全限定类名</b>：字节码里所有类型引用都是全限定名，无法区分「import 后使用简单名」与
 *       「正文里直接写全限定名」——只能在源码层查；</li>
 *   <li><b>{@code @Bean} 条件注解</b>：注解保留策略与合成方式让字节码规则容易漏判，源码层最直接；</li>
 *   <li><b>{@code switch(enum)}</b>：javac 会把它编译成 {@code ordinal()} 查表，字节码规则<b>必然误报</b>，
 *       因此 {@code ordinal} 规则也只能查源码。</li>
 * </ul>
 *
 * <p><b>每条规则都配有效性自检</b>：用合成的违规样本断言规则确实命中，
 * 避免规则写成恒为真却永不报错（母仓已因此踩过三次）。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class SourceConventionTest {

    /** 仓库根目录（测试的工作目录是 architecture-tests 模块）。 */
    private static final Path REPO_ROOT = Paths.get("..").toAbsolutePath().normalize();

    /** 本仓包名前缀。 */
    private static final String OWN_PACKAGE = "cn.ypbin.iot.";

    /**
     * 内联全限定类名：正文中出现任意包名前缀（本仓 / JDK / 第三方），
     * 且不在 import/package 行、不在注释里。
     *
     * <p><b>为什么必须覆盖全部包名</b>：早先只匹配 {@code cn.ypbin.iot.}，导致
     * {@code java.util.concurrent.TimeUnit}、{@code org.eclipse.milo...UaException} 这类
     * 第三方/JDK 内联 FQCN <b>结构性漏判</b>——同一条 R10 规则，第一方被拦、第三方放行，
     * 而后者在实际代码里同样常见（尤其是处理受检异常与并发类型时）。</p>
     */
    private static final Pattern INLINE_FQCN = Pattern.compile(
            "(?<![\\w.$])(?:cn\\.ypbin|java|javax|jakarta|org|com|io)\\.[a-z][\\w]*"
                    + "(?:\\.[a-zA-Z][\\w]*)+");

    /** {@code ordinal()} 调用。 */
    private static final Pattern ORDINAL_CALL = Pattern.compile("\\.ordinal\\s*\\(\\s*\\)");

    private static final Pattern AUTO_CONFIGURATION =
            Pattern.compile("@AutoConfiguration\\b");

    private static final Pattern BEAN_ANNOTATION = Pattern.compile("^\\s*@Bean\\b.*$", Pattern.MULTILINE);

    private static final String CONDITIONAL_ON_MISSING_BEAN = "@ConditionalOnMissingBean";

    @Test
    @DisplayName("SRC-01 禁止内联全限定类名（Javadoc 的 {@link FQCN} 豁免）")
    void noInlineFullyQualifiedNames() {
        List<String> violations = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            List<String> lines = readLines(file);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (isExemptLine(line)) {
                    continue;
                }
                String code = stripJavadocLinks(line);
                Matcher matcher = INLINE_FQCN.matcher(code);
                if (matcher.find()) {
                    violations.add(REPO_ROOT.relativize(file) + ":" + (index + 1) + " -> " + matcher.group());
                }
            }
        }
        assertThat(violations)
                .as("正文禁用内联全限定类名（一律顶部 import）；Javadoc 的 {@link FQCN} 不在此列")
                .isEmpty();
    }

    @Test
    @DisplayName("SRC-02 禁止 ordinal()")
    void noOrdinalUsage() {
        List<String> violations = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            List<String> lines = readLines(file);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (isExemptLine(line)) {
                    continue;
                }
                if (ORDINAL_CALL.matcher(stripJavadocLinks(line)).find()) {
                    violations.add(REPO_ROOT.relativize(file) + ":" + (index + 1));
                }
            }
        }
        assertThat(violations).as("枚举一律用 code 存取，禁止 ordinal()").isEmpty();
    }

    @Test
    @DisplayName("SRC-03 每个 @Bean 方法都必须带 @ConditionalOnMissingBean")
    void everyBeanMustBeOverridable() {
        List<String> violations = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            String content = read(file);
            if (!content.contains("@Bean")) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            for (int index = 0; index < lines.length; index++) {
                if (!BEAN_ANNOTATION.matcher(lines[index]).matches()) {
                    continue;
                }
                // 向下看 12 行内是否出现 @ConditionalOnMissingBean（覆盖 @Bean 与签名之间的注解块）
                boolean found = false;
                for (int probe = index; probe < Math.min(index + 12, lines.length); probe++) {
                    if (lines[probe].contains(CONDITIONAL_ON_MISSING_BEAN)) {
                        found = true;
                        break;
                    }
                    // 遇到方法体开始即停止搜索
                    if (lines[probe].contains("{")) {
                        break;
                    }
                }
                if (!found) {
                    violations.add(REPO_ROOT.relativize(file) + ":" + (index + 1));
                }
            }
        }
        assertThat(violations)
                .as("每个对外 Bean 都必须可被宿主覆盖（AGENTS R2）")
                .isEmpty();
    }

    @Test
    @DisplayName("SRC-04 每个 @AutoConfiguration 都必须在 AutoConfiguration.imports 中登记")
    void everyAutoConfigurationMustBeRegistered() {
        List<String> violations = new ArrayList<>();
        for (Path module : modules()) {
            Path imports = module.resolve(
                    "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
            Set<String> registered = new LinkedHashSet<>();
            if (Files.isRegularFile(imports)) {
                for (String line : readLines(imports)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        registered.add(trimmed);
                    }
                }
            }
            for (Path file : javaFiles(module.resolve("src/main/java"))) {
                String content = read(file);
                if (!AUTO_CONFIGURATION.matcher(content).find()) {
                    continue;
                }
                String fqcn = toFqcn(module, file);
                if (!registered.contains(fqcn)) {
                    violations.add(REPO_ROOT.relativize(file) + " -> 未登记 " + fqcn);
                }
            }
        }
        assertThat(violations)
                .as("未登记的自动配置会被静默忽略（AGENTS R1），必须同步写入 imports 文件")
                .isEmpty();
    }

    @Test
    @DisplayName("SELF-01 内联 FQCN 规则必须能命中违规样本")
    void inlineFqcnRuleMustDetectViolation() {
        String violation = "    return cn.ypbin.iot.core.model.PointValue.good(address, 1, now);";
        assertThat(INLINE_FQCN.matcher("        long t = java.util.concurrent.TimeUnit.SECONDS.toMillis(1);").find())
                .as("JDK 内联 FQCN 同样是 R10 违规，早先只匹配本仓包名导致漏判")
                .isTrue();
        assertThat(INLINE_FQCN.matcher("        throw new org.eclipse.milo.opcua.stack.core.UaException();").find())
                .as("第三方内联 FQCN 同样必须被拦")
                .isTrue();
        assertThat(INLINE_FQCN.matcher("        Duration d = Duration.ofSeconds(1);").find())
                .as("简单类名不得误报")
                .isFalse();
        assertThat(INLINE_FQCN.matcher(stripJavadocLinks(
                        " * @see {@link java.util.concurrent.TimeUnit}")).find())
                .as("Javadoc 的 {@link 全限定名} 仍须豁免")
                .isFalse();
        assertThat(INLINE_FQCN.matcher(stripJavadocLinks(violation)).find())
                .as("规则写错就永远不报错，等于装饰")
                .isTrue();
        String javadocLink = " * @see {@link cn.ypbin.iot.core.model.PointValue}";
        assertThat(INLINE_FQCN.matcher(stripJavadocLinks(javadocLink)).find())
                .as("Javadoc 的 {@link 全限定名} 必须豁免（import 会被 spotless 移除，只能用全限定名）")
                .isFalse();
        String importLine = "import cn.ypbin.iot.core.model.PointValue;";
        assertThat(isExemptLine(importLine)).isTrue();
    }

    @Test
    @DisplayName("SELF-02 ordinal 规则必须能命中违规样本")
    void ordinalRuleMustDetectViolation() {
        assertThat(ORDINAL_CALL.matcher("int i = quality.ordinal();").find()).isTrue();
        assertThat(ORDINAL_CALL.matcher("int i = values()[index];").find())
                .as("合法写法不得误报").isFalse();
        assertThat(isExemptLine("        // 严禁使用 ordinal() 存库"))
                .as("注释行必须被豁免，否则说明文字会触发误报")
                .isTrue();
    }

    @Test
    @DisplayName("SELF-03 自检样本必须覆盖全部四条规则")
    void selfCheckMustCoverAllRules() {
        assertThat(AUTO_CONFIGURATION.matcher("@AutoConfiguration").find()).isTrue();
        assertThat(BEAN_ANNOTATION.matcher("    @Bean").matches()).isTrue();
        assertThat(CONDITIONAL_ON_MISSING_BEAN).isNotBlank();
        assertThat(REPO_ROOT.resolve("ypbin-iot-core")).isDirectory();
    }

    private static boolean isExemptLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("import ")
                || trimmed.startsWith("package ")
                || trimmed.startsWith("*")
                || trimmed.startsWith("/*")
                || trimmed.startsWith("//");
    }

    /**
     * 去掉 Javadoc 的 {@code {@link 全限定名}} / {@code {@linkplain ...}}，它们只能用全限定名。
     */
    private static String stripJavadocLinks(String line) {
        return line.replaceAll("\\{@link\\s+[^}]*\\}", "").replaceAll("\\{@linkplain\\s+[^}]*\\}", "");
    }

    private static String toFqcn(Path module, Path file) {
        Path relative = module.resolve("src/main/java").relativize(file);
        return relative.toString().replace(File.separatorChar, '.').replaceAll("\\.java$", "");
    }

    private static List<Path> modules() {
        try (Stream<Path> stream = Files.list(REPO_ROOT)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("ypbin-iot-"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to list modules under " + REPO_ROOT, ex);
        }
    }

    private static List<Path> mainJavaFiles() {
        List<Path> files = new ArrayList<>();
        for (Path module : modules()) {
            files.addAll(javaFiles(module.resolve("src/main/java")));
        }
        return files;
    }

    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to walk " + root, ex);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read " + file, ex);
        }
    }

    private static List<String> readLines(Path file) {
        return List.of(read(file).split("\n", -1));
    }
}
