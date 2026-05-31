package com.railway.platform.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ApiErrorTest {

  @Test
  void of_populatesAllFields() {
    var error = ApiError.of("NOT_FOUND", "Resource missing", "corr-123");

    assertThat(error.code()).isEqualTo("NOT_FOUND");
    assertThat(error.message()).isEqualTo("Resource missing");
    assertThat(error.correlationId()).isEqualTo("corr-123");
    assertThat(error.timestamp()).isNotNull();
    assertThat(error.fieldErrors()).isNull();
  }

  @Test
  void ofValidation_setsValidationErrorCode() {
    var fieldErrors = List.of(new ApiError.FieldError("name", null, "must not be blank"));
    var error = ApiError.ofValidation("corr-456", fieldErrors);

    assertThat(error.code()).isEqualTo(ErrorCodes.VALIDATION_ERROR);
    assertThat(error.fieldErrors()).hasSize(1);
    assertThat(error.fieldErrors().get(0).field()).isEqualTo("name");
  }
}
