package com.mountainbackend.pagos;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ServicioPagosApplication {

	public static void main(String[] args) {
		SpringApplication.run(ServicioPagosApplication.class, args);
	}

	/** Reloj inyectable: permite probar la regla horaria del detector de fraude. */
	@Bean
	public Clock clock() {
		return Clock.systemDefaultZone();
	}
}
