package com.luciano;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.luciano.config.LlmProperties;
import com.luciano.config.WeatherProperties;

@SpringBootApplication
@EnableConfigurationProperties({LlmProperties.class, WeatherProperties.class})
public class LucianoApplication {

	public static void main(String[] args) {
		SpringApplication.run(LucianoApplication.class, args);
	}

}
