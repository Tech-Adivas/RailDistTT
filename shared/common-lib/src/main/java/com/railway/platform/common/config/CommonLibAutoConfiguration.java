package com.railway.platform.common.config;

import com.railway.platform.common.correlation.CorrelationIdFilter;
import com.railway.platform.common.exception.GlobalExceptionHandler;
import com.railway.platform.common.health.KafkaHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Spring Boot auto-configuration for common-lib.
 *
 * <p>Registers the {@link CorrelationIdFilter}, {@link GlobalExceptionHandler}, and
 * {@link KafkaHealthIndicator} beans in any service that includes common-lib on the classpath.
 * No extra {@code @Import} or component scan needed in service code — Spring Boot's
 * auto-configuration mechanism picks this up from
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 */
@AutoConfiguration
@Import({CorrelationIdFilter.class, GlobalExceptionHandler.class, KafkaHealthIndicator.class})
public class CommonLibAutoConfiguration {}
