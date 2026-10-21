package com.yumi.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HTTP 适配层依赖守卫。
 *
 * <p>规则来源：`docs/一期/05-跨模块接口与事务规则.md` 第 1、2、10 节与交付清单第 1 节：
 * <ul>
 *   <li>面向用户的 HTTP 适配层只能调用本模块公开命令或 Query；</li>
 *   <li>不得直接调用任何模块的 Repository、内部持久化实现或其他模块的内部包；</li>
 *   <li>Participant、Feed、快照冻结与内部编排命令不得直接发布为网页路由。</li>
 * </ul>
 *
 * <p>本测试只校验依赖结构，不校验认证、幂等或事务；这些在 2.11 的真实平台 HTTP 集成测试中验证。
 */
class HttpContractBoundaryTest {

    private static final String ROOT = "com.yumi";

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("HTTP 适配层不直接依赖 Repository")
    void adaptersDoNotDependOnRepositories() {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : httpAdapters()) {
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                if (target.endsWith("Repository") || target.endsWith("RepositoryImpl")) {
                    violations.add(origin.getName() + " -> " + target);
                }
            }
        }

        assertThat(violations)
                .as("HTTP 适配层必须通过公开命令或 Query，不得直连 Repository")
                .isEmpty();
    }

    @Test
    @DisplayName("HTTP 适配层不依赖本模块内部持久化实现或其他模块内部包")
    void adaptersDoNotReachModuleInternals() {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : httpAdapters()) {
            String originModule = moduleOf(origin.getPackageName());
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                String targetModule = moduleOf(target.getPackageName());
                if (targetModule == null) {
                    continue;
                }
                boolean internal = target.getPackageName().matches(
                        "com\\.yumi\\.[a-z]+\\.(application|domain|infrastructure)(\\..*)?");
                if (internal && !targetModule.equals(originModule)) {
                    violations.add(origin.getName() + " -> " + target.getName());
                }
                if (target.getPackageName().startsWith("com.yumi." + originModule + ".infrastructure")) {
                    violations.add(origin.getName() + " -> " + target.getName() + "（本模块持久化实现）");
                }
            }
        }

        assertThat(violations)
                .as("HTTP 适配层不得依赖内部包")
                .isEmpty();
    }

    @Test
    @DisplayName("Participant、Feed 与快照冻结接口不发布为网页路由")
    void internalCollaborationTypesAreNotExposed() {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : httpAdapters()) {
            Set<String> routed = origin.getDirectDependenciesFromSelf().stream()
                    .map(dependency -> dependency.getTargetClass().getName())
                    .filter(name -> name.endsWith("Participant")
                            || name.endsWith("ReportingFeed")
                            || name.endsWith("SourceFeed")
                            || name.endsWith("SnapshotService")
                            || name.endsWith("ReservationParticipant"))
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            for (String target : routed) {
                violations.add(origin.getName() + " -> " + target);
            }
        }

        assertThat(violations)
                .as("内部编排/来源接口不得成为 HTTP 适配层的直接依赖")
                .isEmpty();
    }

    @Test
    @DisplayName("平台 HTTP 契约位于 public api 包，其他模块不依赖平台内部包")
    void platformHttpContractsLiveInApiPackage() {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : classes) {
            String originModule = moduleOf(origin.getPackageName());
            if (originModule == null || originModule.equals("platform")) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName();
                if (targetPackage.startsWith("com.yumi.platform.")
                        && !dependency.getTargetClass().getName().startsWith("com.yumi.platform.api.")
                        && !dependency.getTargetClass().getName().startsWith("com.yumi.platform.package-info")) {
                    violations.add(origin.getName() + " -> " + dependency.getTargetClass().getName());
                }
            }
        }

        assertThat(violations)
                .as("业务模块只能使用 platform.api 中的契约")
                .isEmpty();
    }

    /**
     * HTTP 适配器：模块子域内的 {@code ...api.http} 包。
     *
     * <p>注意 {@code com.yumi.<module>.api.http} 是跨模块共享的线上契约（错误、请求上下文、
     * 十进制序列化），不是某个子域的适配器，因此不在此列。
     */
    private static List<JavaClass> httpAdapters() {
        return classes.stream()
                .filter(javaClass -> javaClass.getPackageName().matches(
                        "com\\.yumi\\.[a-z]+\\..+\\.http(\\..*)?"))
                .toList();
    }

    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(ROOT + ".")) {
            return null;
        }
        String remainder = packageName.substring(ROOT.length() + 1);
        int dot = remainder.indexOf('.');
        return dot < 0 ? remainder : remainder.substring(0, dot);
    }
}
