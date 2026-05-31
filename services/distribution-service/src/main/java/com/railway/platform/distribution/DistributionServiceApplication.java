package com.railway.platform.distribution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Distribution Service — consumes ScheduleComputedEvents and fans out to all
 * distribution channels with WebSocket/STOMP push, saga compensation on failure,
 * and per-channel tracking.
 */
@SpringBootApplication
@EnableScheduling
public class DistributionServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(DistributionServiceApplication.class, args);
  }
}
