package com.sebratel.dashboards.common.cache;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} for {@link TemposAtendenteRefreshJob}. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
