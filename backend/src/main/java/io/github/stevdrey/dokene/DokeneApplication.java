package io.github.stevdrey.dokene;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling is application wide; each job gates itself on its own property (dispatch recovery: ADR 0028 §5).
@SpringBootApplication
@EnableScheduling
public class DokeneApplication {

    public static void main(String[] args) {
        SpringApplication.run(DokeneApplication.class, args);
    }
}
