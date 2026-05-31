package com.railway.platform.query;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Query Service — CQRS read side.
 *
 * <p>Consumes TimetableChangedEvents and ScheduleComputedEvents from Kafka and
 * maintains an eventually-consistent read model in PostgreSQL, with hot reads
 * accelerated by a Redis cache-aside layer.
 */
@SpringBootApplication
@EnableCaching
public class QueryServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(QueryServiceApplication.class, args);
  }
}
