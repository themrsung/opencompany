package com.coreintra.app.api.auth;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.app.api.security.SessionCookies;
import com.coreintra.auth.entity.AuthSession;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.auth.service.SessionService;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.permission.PermissionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing in, staying in, and signing out.
 *
 * <p>There is no password field anywhere in this class, and there is no reset
 * flow to go with it. A sign-in is a username and a TOTP code, or a username and
 * a one-time recovery code.
 *
 * <p>The tokens never reach JavaScript. They are set as HttpOnly cookies and the
 * response body carries only who you are, so an XSS that gets into the page
 * cannot read the session out of it.
 */
@RestController
@RequestMapping("/api/v1/auth/session")
@Tag(name = "Session", description = "Sign in, refresh, sign out, and see where you are signed in.")
public class SessionController {

    private final AuthenticationService authentication;
    private final SessionService sessions;
    private final CurrentPrincipal current;

    public SessionController(AuthenticationService authentication, SessionService sessions,
            CurrentPrincipal current) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.current = current;
    }

    /** A sign-in attempt. */
    public static class SignInRequest {
        @NotBlank
        private String username;
        /** A TOTP code, or a recovery code when {@code recovery} is true. */
        @NotBlank
        private String code;
        private boolean recovery;

        public String getUsername() {
            return username;
        }

        public void setUsername(String value) {
            this.username = value;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String value) {
            this.code = value;
        }

        public boolean isRecovery() {
            return recovery;
        }

        public void setRecovery(boolean value) {
            this.recovery = value;
        }
    }

    /** Who you are. Deliberately not a token. */
    public static class SignedIn {
        private final String accountId;
        private final String displayName;
        private final String employeeId;
        private final boolean master;
        private final String locale;
        private final long remainingRecoveryCodes;

        SignedIn(UserAccount account, long remainingRecoveryCodes) {
            this.accountId = account.id();
            this.displayName = account.displayName();
            this.employeeId = account.employeeId();
            this.master = account.isMaster();
            this.locale = account.locale();
            this.remainingRecoveryCodes = remainingRecoveryCodes;
        }

        public String getAccountId() {
            return accountId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getEmployeeId() {
            return employeeId;
        }

        public boolean isMaster() {
            return master;
        }

        public String getLocale() {
            return locale;
        }

        /** Shown in the UI so nobody discovers they are out of codes at the worst moment. */
        public long getRemainingRecoveryCodes() {
            return remainingRecoveryCodes;
        }
    }

    /** One device, for the active-session list. */
    public static class ActiveSession {
        private final String sessionId;
        private final String userAgent;
        private final String ipAddress;
        private final String createdAt;
        private final String lastUsedAt;

        ActiveSession(AuthSession session) {
            this.sessionId = session.id();
            this.userAgent = session.userAgent();
            this.ipAddress = session.ipAddress();
            this.createdAt = String.valueOf(session.createdAt());
            this.lastUsedAt = session.lastUsedAt() == null ? null : String.valueOf(session.lastUsedAt());
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public String getLastUsedAt() {
            return lastUsedAt;
        }
    }

    @PostMapping
    @SecurityRequirements
    @Operation(summary = "Sign in with a TOTP or recovery code",
            description = "Sets the session cookies. The response body never contains a token.")
    public ResponseEntity<SignedIn> signIn(@Valid @RequestBody SignInRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        String ip = clientAddress(request);
        UserAccount account = body.isRecovery()
                ? authentication.authenticateWithRecoveryCode(body.getUsername(), body.getCode(), ip)
                : authentication.authenticate(body.getUsername(), body.getCode(), ip);

        SessionService.IssuedSession issued =
                sessions.issue(account.id(), request.getHeader("User-Agent"), ip);
        SessionCookies.write(response, issued, SessionCookies.secure(request));

        return ResponseEntity.ok(
                new SignedIn(account, authentication.remainingRecoveryCodes(account.id())));
    }

    /**
     * Exchanges the refresh cookie for a new pair.
     *
     * <p>Unauthenticated by design: the whole point is that it works when the
     * access token has expired. Its credential is the refresh cookie, which is
     * scoped to this path and travels nowhere else.
     */
    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Rotate the session",
            description = "Both tokens rotate. Presenting a superseded refresh token revokes the "
                    + "whole chain, because the only explanation is that it was captured.")
    public ResponseEntity<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        String presented = SessionCookies.read(request, SessionCookies.REFRESH_COOKIE);
        SessionService.IssuedSession issued =
                sessions.refresh(presented, request.getHeader("User-Agent"), clientAddress(request));
        SessionCookies.write(response, issued, SessionCookies.secure(request));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(summary = "Sign out of this device")
    public ResponseEntity<Void> signOut(HttpServletRequest request, HttpServletResponse response) {
        String presented = SessionCookies.read(request, SessionCookies.REFRESH_COOKIE);
        if (presented != null) {
            int separator = presented.indexOf('.');
            if (separator > 0) {
                // Revoked server-side, not merely forgotten by the browser.
                sessions.revoke(presented.substring(0, separator), "signed_out");
            }
        }
        SessionCookies.clear(response, SessionCookies.secure(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/active")
    @Operation(summary = "Every device signed in as you")
    public List<ActiveSession> active() {
        PermissionPrincipal principal = current.require();
        List<ActiveSession> devices = new ArrayList<ActiveSession>();
        for (AuthSession session : sessions.activeSessions(principal.accountId())) {
            devices.add(new ActiveSession(session));
        }
        return Immutables.copyOf(devices);
    }

    @DeleteMapping("/active/{sessionId}")
    @Operation(summary = "Sign a device out remotely")
    public ResponseEntity<Void> revokeDevice(@PathVariable String sessionId) {
        PermissionPrincipal principal = current.require();
        // Only your own devices. Signing someone *else* out is an administrative
        // action with its own permission, not a side effect of this list.
        for (AuthSession session : sessions.activeSessions(principal.accountId())) {
            if (session.id().equals(sessionId)) {
                sessions.revoke(sessionId, "remote_sign_out");
                return ResponseEntity.noContent().build();
            }
        }
        // Same answer whether it is someone else's session or no session at all,
        // so this cannot be used to discover which session ids exist.
        return ResponseEntity.noContent().build();
    }

    /**
     * The caller's address.
     *
     * <p>{@code X-Forwarded-For} is trusted because every deployment of this
     * product puts the application behind its own reverse proxy on the same box;
     * the header cannot arrive from outside without passing through it. If that
     * ever stops being true, this is the line to revisit.
     */
    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            int comma = forwarded.indexOf(',');
            return comma < 0 ? forwarded.trim() : forwarded.substring(0, comma).trim();
        }
        return request.getRemoteAddr();
    }
}
