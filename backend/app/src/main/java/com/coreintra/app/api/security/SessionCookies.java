package com.coreintra.app.api.security;

import com.coreintra.auth.service.SessionService;
import java.time.Duration;
import java.time.OffsetDateTime;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The two session cookies, and the rules that make them safe.
 *
 * <p>Both are {@code HttpOnly}: script never reads them, so an XSS that gets
 * into the page still cannot exfiltrate the session. Both are
 * {@code SameSite=Strict}, which this product can afford because there is no
 * cross-site flow anywhere in it — no OAuth redirect, no embedded third party —
 * and Strict is the only setting that removes CSRF as a category rather than
 * mitigating it.
 *
 * <p>The refresh cookie is scoped to the refresh endpoint's path. An ordinary
 * API call therefore never carries it, so the credential that can mint new
 * sessions is not sprayed across every request in the browser's history,
 * proxies and logs.
 */
public final class SessionCookies {

    public static final String ACCESS_COOKIE = "ci_at";
    public static final String REFRESH_COOKIE = "ci_rt";

    private static final String API_PATH = "/api/v1";
    private static final String REFRESH_PATH = API_PATH + "/auth/session/refresh";

    private SessionCookies() {
    }

    /** Writes both cookies for a freshly issued or rotated session. */
    public static void write(HttpServletResponse response, SessionService.IssuedSession session,
            boolean secure) {
        response.addHeader("Set-Cookie", cookie(ACCESS_COOKIE, session.accessToken(), API_PATH,
                secondsUntil(session.accessExpiresAt()), secure));
        response.addHeader("Set-Cookie", cookie(REFRESH_COOKIE, session.refreshToken(), REFRESH_PATH,
                secondsUntil(session.refreshExpiresAt()), secure));
    }

    /**
     * Clears both, on sign-out.
     *
     * <p>Expiring the cookie is not enough on its own and is not relied on: the
     * session is revoked server-side in the same request. This only stops the
     * browser sending a credential that would be refused anyway.
     */
    public static void clear(HttpServletResponse response, boolean secure) {
        response.addHeader("Set-Cookie", cookie(ACCESS_COOKIE, "", API_PATH, 0, secure));
        response.addHeader("Set-Cookie", cookie(REFRESH_COOKIE, "", REFRESH_PATH, 0, secure));
    }

    public static String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Whether to mark cookies {@code Secure}.
     *
     * <p>True whenever the request arrived over HTTPS, directly or through the
     * reverse proxy. It is not simply hardcoded to true because an on-prem
     * install is sometimes commissioned over plain HTTP on a closed VLAN before
     * a certificate exists, and a Secure cookie on such a box means nobody can
     * sign in at all — with no error that explains why.
     */
    public static boolean secure(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwardedProto);
    }

    private static long secondsUntil(OffsetDateTime expiry) {
        long seconds = Duration.between(OffsetDateTime.now(), expiry).getSeconds();
        return Math.max(0, seconds);
    }

    private static String cookie(String name, String value, String path, long maxAgeSeconds,
            boolean secure) {
        StringBuilder header = new StringBuilder(160);
        header.append(name).append('=').append(value)
                .append("; Path=").append(path)
                .append("; Max-Age=").append(maxAgeSeconds)
                .append("; HttpOnly; SameSite=Strict");
        if (secure) {
            header.append("; Secure");
        }
        return header.toString();
    }
}
