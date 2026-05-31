package com.railway.platform.timetable.api;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactBroker;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pact provider verification test for the Timetable Service REST API.
 *
 * <p>Verifies that this service fulfils the contracts declared by its consumers (operator console,
 * API gateway). Pact files are loaded from src/test/resources/pacts/ for local development;
 * in CI they are fetched from the Pact Broker.
 *
 * <p>To enable Pact Broker in CI, replace @PactFolder with @PactBroker and set:
 *   PACT_BROKER_BASE_URL — URL of the Pact Broker instance
 *   PACT_BROKER_TOKEN    — Auth token (from Vault: secret/data/railway/pact-broker)
 */
@Tag("contract")
@Provider("timetable-service")
// TODO(config): Switch to @PactBroker for CI. Set PACT_BROKER_BASE_URL environment variable.
@PactFolder("src/test/resources/pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public class TimetableControllerPactProducerTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
      .withDatabaseName("timetable_db")
      .withUsername("railway")
      .withPassword("railway_test");

  @LocalServerPort
  private int port;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.cloud.vault.enabled", () -> "false");
    registry.add("spring.config.import", () -> "");
  }

  @BeforeEach
  void setupTarget(PactVerificationContext context) {
    context.setTarget(new HttpTestTarget("localhost", port));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void verifyPact(PactVerificationContext context) {
    context.verifyInteraction();
  }
}
