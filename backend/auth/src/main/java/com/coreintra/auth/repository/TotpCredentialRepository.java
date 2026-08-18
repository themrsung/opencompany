package com.coreintra.auth.repository;

import com.coreintra.auth.entity.TotpCredential;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TotpCredentialRepository extends JpaRepository<TotpCredential, String> {
}
