package com.coreintra.app.api.security;

import com.coreintra.app.api.error.ProblemDetail;
import com.coreintra.app.api.permission.RequestScopedPrincipal;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.runtime.ratelimit.RateLimitDecision;
import com.coreintra.runtime.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-account and per-key rate limiting on the request path (§10).
 *
 * <p>Ordered after authentication, deliberately. A limit keyed on an IP address
 * would count an entire office behind one NAT as a single caller and throttle
 * three hundred people because one script misbehaved; keyed on the account, the
 * runaway integration is the only thing slowed down. The cost is that an
 * unauthenticated request is not limited here — sign-in has its own progressive
 * lockout, which is the right mechanism for that path because it counts
 * failures rather than requests.
 *
 * <p>Reads and writes are counted in separate buckets. A reporting client that
 * polls hard should not be able to exhaust the budget that a person needs in
 * order to approve something.
 */
@Component
@Order(20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String READ_BUCKET = "api.read";
    private static final String WRITE_BUCKET = "api.write";

    private final RateLimiter limiter;
    private final RequestScopedPrincipal current;
    private final ObjectMapper json;

    public RateLimitFilter(RateLimiter limiter, RequestScopedPrincipal current, ObjectMapper json) {
        this.limiter = limiter;
        this.current = current;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Health and info are what a supervisor polls every few seconds to
        // decide whether to restart the process. Throttling them would turn a
        // busy minute into a restart loop.
        String path = request.getRequestURI();
        return path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        PermissionPrincipal principal = current.peek();
        if (principal == null) {
            chain.doFilter(request, response);
            return;
        }

        boolean write = !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod());
        String subjectKind = principal.kind() == PermissionPrincipal.Kind.SERVICE_ACCOUNT
                ? "API_KEY" : "ACCOUNT";
        RateLimitDecision decision = limiter.check(subjectKind, principal.accountId(),
                write ? WRITE_BUCKET : READ_BUCKET);

        response.setHeader("RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("RateLimit-Remaining", Integer.toString(decision.remaining()));

        if (decision.isAllowed()) {
            chain.doFilter(request, response);
            return;
        }
        refuse(response, decision);
    }

    /**
     * Writes the 429 directly.
     *
     * <p>Throwing here would not reach {@code @RestControllerAdvice}: a filter
     * runs outside the dispatcher, so the exception would surface as the
     * container's own error page instead of problem+json. Serialising the body
     * by hand is the price of enforcing the limit before a request reaches a
     * handler, which is the entire point of doing it in a filter.
     */
    private void refuse(HttpServletResponse response, RateLimitDecision decision) throws IOException {
        long seconds = Math.max(1, decision.retryAfter().getSeconds());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), ProblemDetail.of(
                HttpStatus.TOO_MANY_REQUESTS.value(), "rate_limited", "Too many requests",
                "Try again in " + seconds + " seconds."));
    }
}
