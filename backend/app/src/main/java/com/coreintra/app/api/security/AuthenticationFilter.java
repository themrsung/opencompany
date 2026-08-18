package com.coreintra.app.api.security;

import com.coreintra.app.api.permission.RequestScopedPrincipal;
import com.coreintra.auth.service.PrincipalResolver;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the caller for every request, and — critically — unresolves them
 * afterwards.
 *
 * <h2>What this filter does not do</h2>
 *
 * <p>It does not authorise anything. An unauthenticated request is allowed
 * through with no principal, and fails at the point where a handler asks for one
 * or asks the evaluator a question. That is deliberate: authorisation decided in
 * a filter is decided on the URL, and §3 requires every decision be taken on the
 * domain object. A filter that started returning 403s would be a second gate,
 * and a second gate is a gate that disagrees with the first one eventually.
 *
 * <h2>The finally block is the whole point</h2>
 *
 * <p>The principal lives in a {@link ThreadLocal} on a pooled request thread. A
 * leaked one means the next request served by that thread inherits the previous
 * caller's authority — the worst failure available to this class, and silent.
 * Clearing in a {@code finally} is not tidiness; it is the mechanism.
 */
@Component
@Order(10)
public class AuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final String API_KEY_MARKER = "ci_";

    private final PrincipalResolver principals;
    private final RequestScopedPrincipal current;

    public AuthenticationFilter(PrincipalResolver principals, RequestScopedPrincipal current) {
        this.principals = principals;
        this.current = current;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        PermissionPrincipal principal = resolve(request);
        if (principal != null) {
            current.set(principal);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            current.clear();
        }
    }

    private PermissionPrincipal resolve(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER)) {
            String presented = authorization.substring(BEARER.length()).trim();
            if (presented.startsWith(API_KEY_MARKER)) {
                return quietly(presented, true);
            }
            // A bearer token that is not one of ours is refused rather than
            // guessed at. Falling through to the cookie here would let a caller
            // send a bogus Authorization header and still be authenticated as
            // whoever the browser session belongs to.
            return null;
        }

        String accessToken = SessionCookies.read(request, SessionCookies.ACCESS_COOKIE);
        if (Texts.isBlank(accessToken)) {
            return null;
        }
        return quietly(accessToken, false);
    }

    /**
     * Resolves without throwing.
     *
     * <p>A bad credential produces an anonymous request, not a 401 from the
     * filter. The handler then answers 401 if it needed a caller, or serves the
     * public endpoint if it did not — one place decides, and it is the one that
     * knows what the request was for.
     */
    private PermissionPrincipal quietly(String credential, boolean apiKey) {
        try {
            return apiKey ? principals.fromApiKey(credential) : principals.fromAccessToken(credential);
        } catch (PrincipalResolver.UnresolvedPrincipalException e) {
            return null;
        }
    }
}
