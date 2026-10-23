package com.maibao.tech.security;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

    List<ApiKeyEntity> findByOwnerIdOrderByIdDesc(String ownerId);
}
