package com.coreintra.app.config;

import com.coreintra.core.json.BusinessTimeJacksonModule;
import com.coreintra.core.permission.DefaultPermissionEvaluator;
import com.coreintra.core.permission.GrantDirectory;
import com.coreintra.core.permission.OrgDirectory;
import com.coreintra.core.permission.PermissionEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the single permission evaluator and the business-time wire mapping.
 *
 * <p>Both are declared once, application-wide. A second evaluator bean would be
 * a second permission system; registering the Jackson module here rather than
 * per-controller is what stops one endpoint quietly serialising a
 * {@code BusinessInstant} as something else.
 */
@Configuration
public class PermissionConfiguration {

    @Bean
    public PermissionEvaluator permissionEvaluator(OrgDirectory orgDirectory,
            GrantDirectory grantDirectory) {
        return new DefaultPermissionEvaluator(orgDirectory, grantDirectory);
    }

    @Bean
    public BusinessTimeJacksonModule businessTimeJacksonModule() {
        return new BusinessTimeJacksonModule();
    }
}
