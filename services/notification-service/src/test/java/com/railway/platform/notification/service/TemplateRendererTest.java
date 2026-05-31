package com.railway.platform.notification.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

  private final TemplateRenderer renderer = new TemplateRenderer();

  @Test
  void render_replacesKnownVariables() {
    String result = renderer.render(
        "Schedule update for line {{lineId}} effective {{date}}",
        Map.of("lineId", "GWR-PAD-BRI", "date", "2026-06-01"));

    assertThat(result).isEqualTo("Schedule update for line GWR-PAD-BRI effective 2026-06-01");
  }

  @Test
  void render_preservesUnknownPlaceholders() {
    String result = renderer.render(
        "Hello {{name}}, your {{unknown}} is ready",
        Map.of("name", "Alice"));

    assertThat(result).isEqualTo("Hello Alice, your {{unknown}} is ready");
  }

  @Test
  void render_handlesEmptyVariables() {
    String template = "No placeholders here";
    assertThat(renderer.render(template, Map.of())).isEqualTo(template);
  }

  @Test
  void render_multipleOccurrencesReplaced() {
    String result = renderer.render(
        "Line {{lineId}} update — {{lineId}} schedule changed",
        Map.of("lineId", "GWR-PAD-BRI"));

    assertThat(result).isEqualTo("Line GWR-PAD-BRI update — GWR-PAD-BRI schedule changed");
  }

  @Test
  void render_nullTemplateReturnsNull() {
    assertThat(renderer.render(null, Map.of("key", "value"))).isNull();
  }

  @Test
  void render_nullVariablesReturnsTemplateUnchanged() {
    String template = "Hello {{name}}";
    assertThat(renderer.render(template, null)).isEqualTo(template);
  }
}
