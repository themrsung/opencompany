package com.coreintra.app.api.accounting;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The accounting feature flag, as one annotation.
 *
 * <p>Exactly the condition {@code com.coreintra.accounting.service.AccountingConfiguration} carries,
 * written once so the two halves of the switch cannot drift. §9 requires that turning the module
 * off makes its tables, endpoints, MCP tools and UI vanish cleanly; the services disappearing
 * without the endpoints disappearing would not be that. It would be a set of controllers that fail
 * to wire, and Spring would refuse to start the whole application — so a client who never bought
 * accounting could not boot.
 *
 * <p>Repeating the raw {@link ConditionalOnProperty} on every controller and every tool would work
 * until somebody added the twelfth one and forgot. This cannot be forgotten in a way that compiles
 * and then fails at startup: a controller in this package without it does not wire.
 *
 * <p>{@code matchIfMissing = true} matches the services: the module ships on, and turning it off is
 * a decision somebody makes and records in settings.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "coreintra.accounting", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public @interface AccountingEnabled {
}
