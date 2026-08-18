package com.coreintra.app.install;

import org.springframework.data.jpa.repository.JpaRepository;

/** The installation row. At most one, by primary key and by CHECK. */
public interface InstallationRepository extends JpaRepository<InstallationRow, String> {
}
