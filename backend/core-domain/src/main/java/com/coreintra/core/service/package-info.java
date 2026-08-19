/**
 * The org application services - the front door a REST controller calls.
 *
 * <p>ArchUnit forbids a controller from reaching a repository, because a query
 * that has already run cannot be authorised afterwards. Everything a caller can
 * do to the org chart therefore arrives here first, and every method that
 * mutates or reads something sensitive takes a
 * {@link com.coreintra.core.permission.PermissionPrincipal} and routes the
 * decision through {@link com.coreintra.core.permission.PermissionEvaluator}.
 * There is no unchecked variant of any method in this package.
 *
 * <h2>Why there are repository interfaces here</h2>
 *
 * <p>The repositories in {@code com.coreintra.core.org.repository} are shaped
 * for the permission engine: they answer "which positions were live on date D"
 * and "what is the active tree", which is what a check needs and nothing more.
 * This layer asks different questions before it writes - is this code already
 * taken, counting retired rows the unique constraints still count; what did this
 * employee's whole history look like; is anyone still holding this rank. Those
 * reads live here, beside their only callers, rather than widening repositories
 * another agent owns. They are declared as narrow Spring Data interfaces so the
 * service tests can supply an in-memory double instead of a database - this
 * module has no Testcontainers support, and a rule that can only be tested
 * against Postgres is a rule that stops being tested.
 *
 * <h2>Business dates are always passed in, never read from the clock</h2>
 *
 * <p>Every method takes the business date explicitly. A permission target must
 * state the date it is asking about (ADR 0003), and the business day here is the
 * 72-hour window of ADR 0002, so only the caller knows which business date a
 * request belongs to. A service that called {@code LocalDate.now()} would
 * silently answer a different question at 02:00 than at 14:00.
 */
package com.coreintra.core.service;
