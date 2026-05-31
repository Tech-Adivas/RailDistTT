package com.railway.platform.notification.service;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Renders notification templates by substituting {@code {{key}}} placeholders.
 * Unknown placeholders are left as-is so missing variables are visible in delivery logs.
 */
@Component
public class TemplateRenderer {

  public String render(String template, Map<String, String> variables) {
    if (template == null || variables == null || variables.isEmpty()) {
      return template;
    }
    String result = template;
    for (Map.Entry<String, String> entry : variables.entrySet()) {
      result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
    }
    return result;
  }
}
