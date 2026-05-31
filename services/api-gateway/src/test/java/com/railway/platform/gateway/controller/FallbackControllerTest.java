package com.railway.platform.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackControllerTest {

  private final FallbackController controller = new FallbackController();

  @Test
  void getReturns503WithRetryAfterHeader() {
    var exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/fallback/timetable-service")
            .header("X-Correlation-Id", "test-corr-1")
            .build());

    var response = controller.fallbackGet("timetable-service", exchange).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
    assertThat(response.getBody()).containsEntry("error", "service_unavailable");
    assertThat(response.getBody()).containsEntry("service", "timetable-service");
  }

  @Test
  void postReturns503WithRetryAfterHeader() {
    var exchange = MockServerWebExchange.from(
        MockServerHttpRequest.post("/fallback/query-service").build());

    var response = controller.fallbackPost("query-service", exchange).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
    assertThat(response.getBody()).containsEntry("service", "query-service");
  }

  @Test
  void correlationIdEchoedInResponse() {
    var exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/fallback/query-service")
            .header("X-Correlation-Id", "my-trace-id")
            .build());

    var response = controller.fallbackGet("query-service", exchange).block();

    assertThat(response).isNotNull();
    assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("my-trace-id");
  }

  @Test
  void missingCorrelationIdHandledGracefully() {
    var exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/fallback/distribution-service").build());

    var response = controller.fallbackGet("distribution-service", exchange).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(503);
  }
}
