package com.maibao.tech.security;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import com.maibao.tech.security.domain.ApiKeyEntity;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

    List<ApiKeyEntity> findByOwnerIdOrderByIdDesc(String ownerId);
}
