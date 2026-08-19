package com.coreintra.app.api.install;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether the installer is still there.
 *
 * <p>One boolean, and nothing else. A first-boot screen needs to know whether to
 * offer itself; anything more — how many companies exist, when the box was
 * installed, who the master is — would be answering an unauthenticated question
 * about somebody else's system.
 */
@Schema(description = "Whether this installation is still empty and can be opened.")
public class InstallationAvailabilityView {

    private final boolean available;

    public InstallationAvailabilityView(boolean available) {
        this.available = available;
    }

    @Schema(description = "True only while POST /api/v1/install would succeed.")
    public boolean isAvailable() {
        return available;
    }
}
