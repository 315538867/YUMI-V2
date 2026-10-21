package com.yumi.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 九模块公开边界守卫。
 *
 * <p>规则来源：`docs/一期/03-系统总体架构与模块依赖.md` 第 2、6 节，`05-跨模块接口与事务规则.md`
 * 第 1—4 节。任何模块只能通过对方的公开 API 包（`api`）协作，禁止访问其他模块的
 * application / domain / infrastructure 等内部包，也禁止出现基线未声明的依赖方向。
 */
class ModuleBoundaryTest {

    private static final String ROOT = "com.yumi";

    /** 九个顶层模块，顺序与基线 03 第 3 节一致。 */
    static final List<String> MODULES = List.of(
            "platform", "calculation", "customer", "product", "workforce",
            "production", "orders", "finance", "reporting");

    /** 基线 03 第 6 节声明的依赖方向；平台不反向依赖任何业务模块。 */
    private static final Map<String, Set<String>> ALLOWED_DEPENDENCIES = Map.of(
            "platform", Set.of(),
            "calculation", Set.of("platform"),
            "customer", Set.of("platform"),
            "product", Set.of("platform", "calculation"),
            "workforce", Set.of("platform"),
            "production", Set.of("platform", "product", "workforce", "calculation"),
            "orders", Set.of("platform", "customer", "product", "production"),
            "finance", Set.of("platform", "calculation", "product", "workforce", "production", "orders"),
            "reporting", Set.of("platform", "calculation", "customer", "product", "workforce",
                    "production", "orders", "finance"));

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("九个顶层模块包都存在并声明模块边界")
    void nineModulesExist() {
        Set<String> present = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            String module = moduleOf(javaClass.getPackageName());
            if (module != null) {
                present.add(module);
            }
        }

        assertThat(present)
                .as("顶层模块必须与基线 03 第 3 节完全一致")
                .containsExactlyInAnyOrderElementsOf(MODULES);
    }

    @Test
    @DisplayName("每个模块都有 package-info 说明边界")
    void everyModuleDeclaresPackageInfo() {
        List<String> missing = new ArrayList<>();
        for (String module : MODULES) {
            boolean found = classes.stream()
                    .anyMatch(javaClass -> javaClass.getPackageName().equals(ROOT + "." + module)
                            && javaClass.getSimpleName().equals("package-info"));
            if (!found) {
                missing.add(module);
            }
        }

        assertThat(missing).as("缺少 package-info.java 的模块").isEmpty();
    }

    @Test
    @DisplayName("模块内部包不被其他模块访问")
    void internalsAreNotAccessedByOtherModules() {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : classes) {
            String originModule = moduleOf(origin.getPackageName());
            if (originModule == null) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                String targetModule = moduleOf(target.getPackageName());
                if (targetModule == null || targetModule.equals(originModule)) {
                    continue;
                }
                if (!isPublicApi(target.getPackageName())) {
                    violations.add(origin.getName() + " -> " + target.getName());
                }
            }
        }

        assertThat(violations)
                .as("跨模块只能访问对方 api 包")
                .isEmpty();
    }

    @Test
    @DisplayName("依赖方向与基线一致：orders 不依赖 finance，production 不依赖 orders/finance")
    void dependenciesFollowBaselineDirection() {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : classes) {
            String originModule = moduleOf(origin.getPackageName());
            if (originModule == null) {
                continue;
            }
            Set<String> allowed = ALLOWED_DEPENDENCIES.get(originModule);
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String targetModule = moduleOf(dependency.getTargetClass().getPackageName());
                if (targetModule == null || targetModule.equals(originModule)) {
                    continue;
                }
                if (!allowed.contains(targetModule)) {
                    violations.add(originModule + " -> " + targetModule + " (" + origin.getName() + ")");
                }
            }
        }

        assertThat(violations)
                .as("未在基线 03 第 6 节声明的模块依赖")
                .isEmpty();
    }

    @Test
    @DisplayName("根包只放应用入口，不放业务类型")
    void rootPackageOnlyHoldsEntryPoint() {
        List<String> offenders = classes.stream()
                .filter(javaClass -> javaClass.getPackageName().equals(ROOT))
                .map(JavaClass::getName)
                .filter(name -> !name.endsWith("YumiApplication") && !name.endsWith("package-info"))
                .sorted()
                .toList();

        assertThat(offenders).as("根包不应承载业务类型").isEmpty();
    }

    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(ROOT + ".")) {
            return null;
        }
        String remainder = packageName.substring(ROOT.length() + 1);
        String head = remainder.contains(".") ? remainder.substring(0, remainder.indexOf('.')) : remainder;
        return MODULES.contains(head) ? head : null;
    }

    /**
     * 公开 API 包判定。
     *
     * <p>模块根下的 {@code api} 包及其子域 {@code <subdomain>.api} 包是跨模块公开契约
     * （例如 {@code com.yumi.platform.api.http}、{@code com.yumi.orders.aftersales.api}）；
     * application、infrastructure 等内部包不得被其他模块访问。
     */
    static boolean isPublicApi(String packageName) {
        return packageName.matches("com\\.yumi\\.[a-z]+(\\.[a-z]+)?\\.api(\\..*)?");
    }

    @SuppressWarnings("unused")
    private static final org.assertj.core.api.Condition<JavaClass> IN_PLATFORM =
            new org.assertj.core.api.Condition<>(resideInAPackage("com.yumi.platform.."), "in platform");

    /** 便于后续规则复用的模块依赖快照。 */
    static Map<String, Set<String>> allowedDependencies() {
        return new LinkedHashMap<>(ALLOWED_DEPENDENCIES);
    }
}
