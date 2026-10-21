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
 * 计算中心边界：纯代码模块，不依赖数据库、Spring 持久化或业务模块。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §3.2、§8：
 * 计算中心不拥有数据库、Repository、HTTP Controller、业务快照与生产事实；
 * 其他模块不得复制其公式实现。
 */
class CalculationBoundaryTest {

    private static final Set<String> FORBIDDEN_PREFIXES = Set.of(
            "org.springframework.jdbc",
            "org.springframework.transaction",
            "org.springframework.web",
            "jakarta.servlet",
            "javax.sql",
            "com.zaxxer.hikari",
            "org.flywaydb");

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
                .importPackages("com.yumi.calculation");
    }

    @Test
    @DisplayName("计算中心不依赖持久化、事务、Web 或 Servlet")
    void calculationHasNoInfrastructureDependencies() {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                for (String prefix : FORBIDDEN_PREFIXES) {
                    if (target.startsWith(prefix)) {
                        violations.add(javaClass.getName() + " -> " + target);
                    }
                }
            }
        }

        assertThat(violations).as("计算中心不得依赖基础设施").isEmpty();
    }

    @Test
    @DisplayName("计算中心只依赖 platform.api，不依赖其他业务模块")
    void calculationOnlyDependsOnPlatformApi() {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                if (target.startsWith("com.yumi.") && !target.startsWith("com.yumi.calculation.")) {
                    if (!target.startsWith("com.yumi.platform.api.")
                            && !target.equals("com.yumi.platform.package-info")) {
                        violations.add(javaClass.getName() + " -> " + target);
                    }
                }
            }
        }

        assertThat(violations).as("计算中心只能依赖 platform.api").isEmpty();
    }

    @Test
    @DisplayName("计算中心没有 Repository、Controller 与数据库访问类型")
    void calculationHasNoPersistenceOrHttpTypes() {
        List<String> offenders = classes.stream()
                .map(JavaClass::getName)
                .filter(name -> name.endsWith("Repository")
                        || name.endsWith("Controller")
                        || name.contains(".infrastructure."))
                .toList();

        assertThat(offenders).as("计算中心不得出现持久化或 HTTP 适配类型").isEmpty();
    }

    @Test
    @DisplayName("计算中心没有同名公式复制到其他模块")
    void noDuplicateFormulaImplementations() {
        JavaClasses all = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
                .importPackages("com.yumi");

        List<String> duplicates = all.stream()
                .filter(javaClass -> !javaClass.getPackageName().startsWith("com.yumi.calculation"))
                .map(JavaClass::getSimpleName)
                .filter(name -> Set.of(
                        "ProductBudgetCalculatorImpl",
                        "WorkloadCalculatorImpl",
                        "MoneyAllocationCalculatorImpl",
                        "MonthlyGlueAllowanceCalculatorImpl",
                        "CostAmountCalculatorImpl",
                        "CapacityCalculatorImpl",
                        "GlueWeightAllocatorImpl").contains(name))
                .toList();

        assertThat(duplicates).as("其他模块不得复制计算中心公式").isEmpty();
    }
}
