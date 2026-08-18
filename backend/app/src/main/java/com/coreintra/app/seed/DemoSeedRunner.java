package com.coreintra.app.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * {@code make seed}, from the application's point of view.
 *
 * <p>Registered only under the {@code seed} profile, and it checks the profile again on the way
 * in. Belt and braces on purpose: the class-level {@link Profile} is what normally keeps this
 * out of a running installation, and the explicit check is what keeps it out if somebody ever
 * registers the bean by hand or copies the class into another configuration.
 *
 * <h2>Exit codes</h2>
 *
 * <p>A failure is rethrown, which fails the Spring context and gives the process a non-zero exit
 * status with the cause in the log. That matters more than it looks: the failure mode this
 * avoids is a half-seeded database behind a command that appeared to succeed, which nobody
 * discovers until they click the one thing that is missing.
 *
 * <h2>Credentials on the console</h2>
 *
 * <p>The demo account's recovery codes are printed here and nowhere else. They are never written
 * to a file, and this class does not exist outside the seed profile, so there is no path by
 * which a real installation prints a working credential. A demo box whose only account cannot
 * sign in is not a demo, which is why they are printed at all.
 */
@Component
@Profile(DemoSeedRunner.SEED_PROFILE)
public class DemoSeedRunner implements ApplicationRunner {

    /** The one profile under which any of this exists. */
    public static final String SEED_PROFILE = "seed";

    private static final Logger LOG = LoggerFactory.getLogger(DemoSeedRunner.class);

    private final DemoSeed seed;
    private final Environment environment;
    private final boolean runOnStart;

    DemoSeedRunner(DemoSeed seed, Environment environment,
            @Value("${coreintra.seed.run-on-start:true}") boolean runOnStart) {
        this.seed = seed;
        this.environment = environment;
        this.runOnStart = runOnStart;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!runOnStart) {
            // Set by the integration test, which drives the seed itself so that it can run it
            // twice and assert on both runs.
            return;
        }
        if (!environment.acceptsProfiles(Profiles.of(SEED_PROFILE))) {
            throw new SeedRefusedException(
                    "데모 시드는 seed 프로파일에서만 실행됩니다. (The demo seed refuses to run outside "
                            + "the \"seed\" profile.)");
        }

        long startedAt = System.currentTimeMillis();
        SeedSummary summary;
        try {
            summary = seed.run();
        } catch (SeedRefusedException refusal) {
            LOG.error("데모 시드를 실행하지 않았습니다. {}", refusal.getMessage());
            throw refusal;
        } catch (RuntimeException failure) {
            LOG.error("데모 시드가 실패하여 아무것도 남기지 않았습니다. 트랜잭션이 되돌려집니다. "
                    + "(The demo seed failed; its transaction is rolled back and nothing was "
                    + "left behind.)", failure);
            throw failure;
        }
        LOG.info("{}", summary.describe());
        LOG.info("데모 시드 소요 시간: {}ms", Long.valueOf(System.currentTimeMillis() - startedAt));
    }
}
