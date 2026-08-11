package io.github.ramossvitor.herald;

import org.springframework.boot.SpringApplication;

public class TestHeraldApplication {

	public static void main(String[] args) {
		SpringApplication.from(HeraldApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
