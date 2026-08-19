package com.coreintra.app.config;

import com.coreintra.auth.service.PrincipalResolver;
import com.coreintra.runtime.support.TemporaryMasterGrantRow;
import com.coreintra.runtime.support.TemporaryMasterService;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Joins the two halves of the temporary-master feature.
 *
 * <p>The authentication module needs to know whether an account is currently a
 * support session, so that the principal it builds is flagged
 * {@code TEMPORARY_MASTER} and every read it performs is logged. The feature
 * itself lives in the platform runtime. Neither module may depend on the other:
 * the runtime records what authentication does, so a dependency from the
 * runtime to auth would be a cycle waiting to happen, and one the other way
 * would drag the whole support feature into the sign-in path.
 *
 * <p>So authentication declares an SPI with a default of "nobody", and the
 * assembly module — this one, the only place that legitimately knows about
 * everything — supplies the real answer. If this bean is ever removed, the
 * system does not break: it behaves as though there were no live support
 * session, which is the safe direction to fail in.
 */
@Configuration
public class TemporaryMasterWiring {

    @Bean
    public PrincipalResolver.TemporaryMasterDirectory temporaryMasterDirectory(
            final TemporaryMasterService sessions) {
        return new PrincipalResolver.TemporaryMasterDirectory() {
            @Override
            public boolean isLiveTemporaryMaster(String accountId) {
                // A grant exists for the whole of its retained life, expired and
                // revoked ones included, because the session report is built
                // from it afterwards. Only a live one changes who the caller is.
                TemporaryMasterGrantRow grant = sessions.grantFor(accountId);
                return grant != null && grant.isActiveAt(OffsetDateTime.now());
            }
        };
    }
}
