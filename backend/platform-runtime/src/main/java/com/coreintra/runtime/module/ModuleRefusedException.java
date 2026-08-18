package com.coreintra.runtime.module;

/**
 * A module may not do what it is asking to do.
 *
 * <p>Thrown at installation and again at start-up, because the jar on disk
 * today is not necessarily the one that was installed. The message names the
 * specific permissions or namespace at issue: "module refused" alone leaves a
 * client developer guessing, and guessing produces support tickets that say
 * "it does not work".
 */
public class ModuleRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModuleRefusedException(String message) {
        super(message);
    }
}
