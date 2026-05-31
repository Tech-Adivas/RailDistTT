package com.railway.platform.gateway.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorrelationIdGlobalFilterTest {

  private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

  @Test
  void whenNoCorrelationIdPresent_thenUuidMintedAndPropagated() {
    var request = MockServerHttpRequest.get("/api/v1/timetables").build();
    var exchange = MockServerWebExchange.from(request);
    var chain = mockChain(exchange);

    filter.filter(exchange, chain).block();

    String downstream = exchange.getRequest().getHeaders()
        .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
    assertThat(downstream).isNotNull().isNotBlank();
    // UUID format
    assertThat(downstream).matches("[0-9a-f-]{36}");
  }

  @Test
  void whenCorrelationIdAlreadyPresent_thenExistingIdPreserved() {
    String existingId = "existing-correlation-123";
    var request = MockServerHttpRequest.get("/api/v1/timetables")
        .header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, existingId)
        .build();
    var exchange = MockServerWebExchange.from(request);
    var chain = mockChain(exchange);

    filter.filter(exchange, chain).block();

    String downstream = exchange.getRequest().getHeaders()
        .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
    assertThat(downstream).isEqualTo(existingId);
  }

  @Test
  void filterOrder_isHighestPrecedencePlusOne() {
    assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE + 1);
  }

  private GatewayFilterChain mockChain(MockServerWebExchange exchange) {
    GatewayFilterChain chain = mock(GatewayFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());
    return chain;
  }
}
