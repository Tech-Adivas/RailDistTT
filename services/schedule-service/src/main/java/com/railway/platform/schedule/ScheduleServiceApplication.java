package com.railway.platform.schedule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Schedule Service — stateless Kafka consumer that computes effective schedules
 * from TimetableChangedEvents and emits ScheduleComputedEvents.
 */
@SpringBootApplication
public class ScheduleServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ScheduleServiceApplication.class, args);
  }
}
