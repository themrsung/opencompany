package com.coreintra.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CoreIntra - self-hosted B2B intranet.
 *
 * <p>Single deployable: this JAR plus PostgreSQL plus the conversion worker.
 * See {@code docs/deploy/} for both supported deployment shapes.
 */
@SpringBootApplication(scanBasePackages = "com.coreintra")
public class CoreIntraApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreIntraApplication.class, args);
    }
}
