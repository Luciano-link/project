package com.luciano;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.luciano.config.LlmProperties;
import com.luciano.config.MailProperties;
import com.luciano.config.SecurityProperties;
import com.luciano.config.WeatherProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({LlmProperties.class, WeatherProperties.class, MailProperties.class, SecurityProperties.class})
public class LucianoApplication {

	public static void main(String[] args) {
		SpringApplication.run(LucianoApplication.class, args);
	}

}
