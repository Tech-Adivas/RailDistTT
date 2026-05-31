package com.railway.platform.timetable.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit tests enforcing DDD layering boundaries.
 *
 * <p>Rules enforced:
 * <ul>
 *   <li>Domain layer must not depend on Spring, JPA, or Kafka (pure POJO domain).
 *   <li>Domain layer must not depend on the infrastructure or API layers.
 *   <li>Application layer must not depend on the API (REST) layer.
 *   <li>Infrastructure layer must not depend on the API layer.
 * </ul>
 */
@Tag("unit")
class ArchitectureBoundaryTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes = new ClassFileImporter()
        .importPackages("com.railway.platform.timetable");
  }

  @Test
  void domainLayer_mustNotDependOnSpringJpa() {
    ArchRule rule = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.data.jpa..",
            "jakarta.persistence..",
            "org.springframework.stereotype..",
            "org.springframework.web..");
    rule.check(classes);
  }

  @Test
  void domainLayer_mustNotDependOnInfrastructure() {
    ArchRule rule = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAPackage("..infrastructure..");
    rule.check(classes);
  }

  @Test
  void domainLayer_mustNotDependOnApiLayer() {
    ArchRule rule = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAPackage("..api..");
    rule.check(classes);
  }

  @Test
  void applicationLayer_mustNotDependOnApiLayer() {
    ArchRule rule = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat()
        .resideInAPackage("..api..");
    rule.check(classes);
  }

  @Test
  void infrastructureLayer_mustNotDependOnApiLayer() {
    ArchRule rule = noClasses()
        .that().resideInAPackage("..infrastructure..")
        .should().dependOnClassesThat()
        .resideInAPackage("..api..");
    rule.check(classes);
  }
}
