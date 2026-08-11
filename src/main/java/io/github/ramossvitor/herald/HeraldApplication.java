package io.github.ramossvitor.herald;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HeraldApplication {

	public static void main(String[] args) {
		SpringApplication.run(HeraldApplication.class, args);
	}

}
