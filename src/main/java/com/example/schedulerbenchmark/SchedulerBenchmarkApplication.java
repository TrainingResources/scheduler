package com.example.schedulerbenchmark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SchedulerBenchmarkApplication {

	public static void main(String[] args) {
		SpringApplication.run(SchedulerBenchmarkApplication.class, args);
	}

}
