package com.coreintra.runtime.support;

import org.springframework.data.repository.Repository;

import java.util.Optional;

/** The one-row kill switch. Readable and raisable; never deleted, never lowered. */
public interface TemporaryMasterSwitchRepository extends Repository<TemporaryMasterSwitchRow, String> {

    TemporaryMasterSwitchRow save(TemporaryMasterSwitchRow row);

    Optional<TemporaryMasterSwitchRow> findById(String installation);
}
