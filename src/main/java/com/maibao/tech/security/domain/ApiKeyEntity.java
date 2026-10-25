package com.maibao.tech.security.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 密钥表：只存密文与脱敏串，明文永不落库。 */
@Entity
@Table(name = "api_key",
        indexes = @Index(name = "idx_apikey_owner", columnList = "owner_id, id"))
public class ApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "cipher_text", nullable = false, length = 512)
    private String cipherText;

    @Column(name = "masked", nullable = false, length = 64)
    private String masked;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ApiKeyEntity() {
    }

    public ApiKeyEntity(String ownerId, String projectId, String provider, String cipherText, String masked) {
        this.ownerId = ownerId;
        this.projectId = projectId;
        this.provider = provider;
        this.cipherText = cipherText;
        this.masked = masked;
    }

    public Long getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getProvider() {
        return provider;
    }

    public String getCipherText() {
        return cipherText;
    }

    public String getMasked() {
        return masked;
    }
}
