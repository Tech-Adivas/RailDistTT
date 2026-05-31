package com.railway.platform.timetable;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Timetable Service.
 *
 * <p>EnableScheduling is required for the timetable activation job that transitions APPROVED
 * timetables to ACTIVE when their effective date arrives.
 */
@SpringBootApplication
@EnableScheduling
public class TimetableServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(TimetableServiceApplication.class, args);
  }
}
