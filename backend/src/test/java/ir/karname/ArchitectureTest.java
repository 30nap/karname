package ir.karname;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Module boundaries of the modular monolith. */
@AnalyzeClasses(packages = "ir.karname", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule modulesHaveNoCycles = slices().matching("ir.karname.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule commonIsIndependent = noClasses().that().resideInAPackage("ir.karname.common..")
            .should().dependOnClassesThat().resideOutsideOfPackages("ir.karname.common..", "java..", "javax..", "jakarta..",
                    "org.springframework..", "org.slf4j..", "tools.jackson..", "com.fasterxml..");

    @ArchTest
    static final ArchRule coreModulesDoNotDependOnFeatures = noClasses().that()
            .resideInAnyPackage("ir.karname.commodity..", "ir.karname.account..", "ir.karname.category..", "ir.karname.transaction..")
            .should().dependOnClassesThat().resideInAnyPackage("ir.karname.ledger..", "ir.karname.report..", "ir.karname.ai..");
}
