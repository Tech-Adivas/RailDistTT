package com.railway.platform.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CorrelationIdHolderTest {

  @AfterEach
  void cleanup() {
    CorrelationIdHolder.clear();
  }

  @Test
  void set_and_get_roundTrip() {
    CorrelationIdHolder.set("test-corr-id");
    assertThat(CorrelationIdHolder.get()).isEqualTo("test-corr-id");
  }

  @Test
  void clear_removesFromMdc() {
    CorrelationIdHolder.set("test-corr-id");
    CorrelationIdHolder.clear();
    assertThat(CorrelationIdHolder.get()).isNull();
  }

  @Test
  void get_returnsNull_whenNotSet() {
    assertThat(CorrelationIdHolder.get()).isNull();
  }
}
