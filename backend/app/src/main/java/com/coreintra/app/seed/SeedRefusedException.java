package com.coreintra.app.seed;

/**
 * Thrown when the seed will not run: the wrong profile, or a database that already holds
 * somebody's real organisation.
 *
 * <p>The dangerous version of this feature is the one that runs anyway. A demo seed that a
 * misconfigured {@code SPRING_PROFILES_ACTIVE} can point at a client's installation is a
 * data-loss incident waiting for a bad deployment, so the refusal is loud, checked before
 * anything is written, and fatal to the process.
 */
public class SeedRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SeedRefusedException(String message) {
        super(message);
    }
}
