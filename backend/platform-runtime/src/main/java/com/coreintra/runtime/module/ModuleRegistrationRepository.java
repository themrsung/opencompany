package com.coreintra.runtime.module;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Modules are retired, not deleted.
 *
 * <p>An uninstalled module leaves a schema behind and, if it was in-process,
 * leaves audit rows naming it as the actor. A deleted registration would make
 * both unattributable.
 */
public interface ModuleRegistrationRepository extends Repository<ModuleRegistrationRow, String> {

    ModuleRegistrationRow save(ModuleRegistrationRow row);

    Optional<ModuleRegistrationRow> findById(String id);

    Optional<ModuleRegistrationRow> findByCompanyIdAndModuleId(String companyId, String moduleId);

    /** Namespaces are unique installation-wide, not per company. */
    Optional<ModuleRegistrationRow> findBySchemaNamespace(String schemaNamespace);

    List<ModuleRegistrationRow> findByCompanyIdAndRetiredAtIsNullOrderByInstalledAtDesc(String companyId);
}
