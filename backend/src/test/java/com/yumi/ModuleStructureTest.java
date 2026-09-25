package com.yumi;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleStructureTest {

    @Test
    void declaresTopLevelBusinessModulesPlusSharedAndCalculation() {
        var modules = ApplicationModules.of(YumiApplication.class);

        assertThat(modules.stream().map(module -> module.getName()))
                .containsExactlyInAnyOrder("identity", "catalog", "orders", "inventory", "production",
                        "reports", "files", "shared", "calculation");
    }

    @Test
    void calculationModuleHasNoOutgoingDependencies() {
        var modules = ApplicationModules.of(YumiApplication.class);
        var calculation = modules.getModuleByName("calculation").orElseThrow();

        assertThat(calculation.getDependencies(modules).uniqueModules().map(ApplicationModule::getName))
                .isEmpty();
    }

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of(YumiApplication.class).verify();
    }
}
