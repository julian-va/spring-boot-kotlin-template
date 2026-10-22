package jvacloud.spring_boot_kotlin_template

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["jvacloud.spring_boot_kotlin_template"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ArchitectureTest {

    companion object {

        @JvmField
        @ArchTest
        val domainShouldNotDependOnInfrastructure: ArchRule =
            noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                .allowEmptyShould(true)

        @JvmField
        @ArchTest
        val useCasesShouldOnlyDependOnDomain: ArchRule =
            classes()
                .that().resideInAPackage("..domain.usecase..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                    "..domain..",
                    "java..",
                    "kotlin..",
                    "org.slf4j..",
                    "org.springframework.stereotype..",
                    "org.springframework.transaction.annotation..",
                )
                .allowEmptyShould(true)

        @JvmField
        @ArchTest
        val providersShouldNotDependOnDomain: ArchRule =
            noClasses()
                .that().resideInAPackage("..infrastructure.provider..")
                .should().dependOnClassesThat().resideInAPackage("..domain..")
                .allowEmptyShould(true)

        @JvmField
        @ArchTest
        val entrypointsShouldNotDependOnAdaptersOrProviders: ArchRule =
            noClasses()
                .that().resideInAPackage("..infrastructure.entrypoint..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..infrastructure.adapter..", "..infrastructure.provider..")
                .allowEmptyShould(true)
    }
}
