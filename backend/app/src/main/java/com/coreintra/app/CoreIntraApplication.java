package com.coreintra.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * CoreIntra — self-hosted B2B intranet.
 *
 * <p>Single deployable: this JAR plus PostgreSQL plus the conversion worker.
 * See {@code docs/deploy/} for both supported deployment shapes.
 *
 * <p>The three scan annotations all point at {@code com.coreintra} rather than
 * this package. Component scanning follows {@code scanBasePackages}, but
 * entity and repository scanning default to the application class's own
 * package regardless — so without the latter two, every entity and repository
 * in the domain modules is invisible and the context fails at startup.
 */
@SpringBootApplication(scanBasePackages = "com.coreintra")
@EntityScan(basePackages = "com.coreintra")
@EnableJpaRepositories(basePackages = "com.coreintra")
public class CoreIntraApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreIntraApplication.class, args);
    }
}
