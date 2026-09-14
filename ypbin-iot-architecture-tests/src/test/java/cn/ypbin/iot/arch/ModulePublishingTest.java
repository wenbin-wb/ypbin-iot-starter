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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模块发布边界门禁。
 *
 * <p>把「非发布模块必须离开发布反应堆」这条约定变成构建失败。
 * <b>背景是母仓一次真实事故</b>：{@code maven.deploy.skip=true} 对
 * {@code central-publishing-maven-plugin}（{@code extensions=true} 接管 deploy 生命周期）
 * <b>无效</b>，且它的 {@code excludeArtifacts} 只按 artifactId 比对，
 * 于是未签名的架构测试模块混进了 Central 上传包，整个 deployment 校验 FAILED。
 * 正确做法是把它放进 {@code activeByDefault} 的 dev-only profile，由 {@code -Prelease} 排除。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
class ModulePublishingTest {

    private static Path repoRoot;

    private static String rootPom;

    @BeforeAll
    static void loadRootPom() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.exists(current.resolve("ypbin-iot-architecture-tests"))) {
                repoRoot = current;
                break;
            }
            current = current.getParent();
        }
        if (repoRoot == null) {
            throw new IllegalStateException("未能定位仓库根目录：" + Path.of("").toAbsolutePath());
        }
        rootPom = Files.readString(repoRoot.resolve("pom.xml"), StandardCharsets.UTF_8);
    }

    /** 取根 pom 顶层（profiles 之前）的 modules 列表。 */
    static List<String> topLevelModules(String pom) {
        Matcher matcher = Pattern.compile("<modules>(.*?)</modules>", Pattern.DOTALL).matcher(pom);
        return matcher.find() ? moduleEntries(matcher.group(1)) : List.of();
    }

    /** 取指定 profile 的 modules 列表。 */
    static List<String> profileModules(String pom, String profileId) {
        Matcher profile = Pattern.compile(
                "<profile>\\s*<id>" + Pattern.quote(profileId) + "</id>(.*?)</profile>", Pattern.DOTALL)
                .matcher(pom);
        if (!profile.find()) {
            return List.of();
        }
        Matcher modules = Pattern.compile("<modules>(.*?)</modules>", Pattern.DOTALL)
                .matcher(profile.group(1));
        return modules.find() ? moduleEntries(modules.group(1)) : List.of();
    }

    private static List<String> moduleEntries(String modulesBlock) {
        List<String> entries = new ArrayList<>();
        Matcher matcher = Pattern.compile("<module>([^<]+)</module>").matcher(modulesBlock);
        while (matcher.find()) {
            entries.add(matcher.group(1).trim());
        }
        return entries;
    }

    /** 该模块 pom 是否声明了「不发布」。 */
    private static boolean skipPublishing(Path moduleDir) throws IOException {
        Path pom = moduleDir.resolve("pom.xml");
        if (!Files.exists(pom)) {
            return false;
        }
        String content = Files.readString(pom, StandardCharsets.UTF_8);
        // 不用「裸字符串包含 true」判定：那样 `${...}` 间接引用（如
        // `<maven.deploy.skip>${iot.skip.publish}</maven.deploy.skip>` + 属性=true）
        // 会被判成「发布模块」而绕过门禁（复审已用变异验证过这个绕过）。
        // 改为：只要声明了 deploy.skip/gpg.skip 且值**不是明确的 false**，就按「不发布意图」处理。
        return skipDeclared(content, "maven.deploy.skip") || skipDeclared(content, "gpg.skip")
                // central-publishing 插件自身的跳过开关同样是「不发布意图」
                || skipDeclared(content, "skipPublishing");
    }

    /** 该 pom 是否声明了某跳过开关且值不是明确的 false。 */
    private static boolean skipDeclared(String pomContent, String property) {
        java.util.regex.Matcher matcher = Pattern.compile(
                "<" + Pattern.quote(property) + ">\\s*([^<]*?)\\s*</" + Pattern.quote(property) + ">")
                .matcher(pomContent);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!"false".equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("MTP-01 清单解析自检：顶层 modules 与 dev-only profile 必须解析出预期内容")
    void moduleListParsingShouldBeAccurate() {
        List<String> top = topLevelModules(rootPom);
        List<String> devOnly = profileModules(rootPom, "dev-only");

        assertThat(top).as("顶层 modules 解析失败或为空").contains("ypbin-iot-core", "ypbin-iot-runtime");
        assertThat(devOnly)
                .as("dev-only profile 未解析出模块 —— 门禁会静默失效（解析器与 pom 结构脱节）")
                .contains("ypbin-iot-architecture-tests");
        assertThat(top)
                .as("非发布模块不得出现在顶层 modules（否则未签名产物会混进 Central 上传包）")
                .doesNotContain("ypbin-iot-architecture-tests");
    }

    @Test
    @DisplayName("MTP-02 声明不发布的模块必须由 dev-only profile 承载")
    void nonPublishedModulesShouldBeOutOfReleaseReactor() throws IOException {
        Set<String> top = new LinkedHashSet<>(topLevelModules(rootPom));
        Set<String> devOnly = new LinkedHashSet<>(profileModules(rootPom, "dev-only"));
        Set<String> problems = new LinkedHashSet<>();

        try (Stream<Path> stream = Files.walk(repoRoot)) {
            for (Path pom : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .toList()) {
                Path moduleDir = pom.getParent();
                if (moduleDir.equals(repoRoot) || !skipPublishing(moduleDir)) {
                    continue;
                }
                String name = repoRoot.relativize(moduleDir).toString();
                if (top.contains(name)) {
                    problems.add(name + " → 在顶层 <modules> 中：会导致未签名产物混进上传包，"
                            + "请移入 dev-only profile");
                } else if (!devOnly.contains(name)) {
                    problems.add(name + " → 既不在顶层也不在 dev-only profile 中，模块不参与任何常规构建");
                }
            }
        }

        assertThat(problems)
                .as("非发布模块必须由根 pom 的 dev-only profile 承载（-Prelease 会跳过该 profile），"
                        + "且 -Psbom 中也要补一份以保证 SBOM 覆盖完整")
                .isEmpty();
    }

    @Test
    @DisplayName("MTP-03 dev-only profile 里的模块必须确实是非发布模块（防止把发布模块误挪出去）")
    void devOnlyProfileShouldOnlyContainNonPublishedModules() throws IOException {
        Set<String> problems = new LinkedHashSet<>();
        for (String name : profileModules(rootPom, "dev-only")) {
            Path moduleDir = repoRoot.resolve(name);
            if (!Files.isDirectory(moduleDir)) {
                problems.add(name + " → 目录不存在");
            } else if (!skipPublishing(moduleDir)) {
                problems.add(name + " → 未声明 maven.deploy.skip/gpg.skip，却放在 dev-only 中，会导致它不被发布");
            }
        }
        assertThat(problems).isEmpty();
    }
}
