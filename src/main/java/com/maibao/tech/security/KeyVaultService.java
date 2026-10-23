package com.maibao.tech.security;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.maibao.tech.common.BizException;

/**
 * 密钥服务：写入加密、读出脱敏、使用前校验归属。
 *
 * <p>安全要点：<br>
 * 1) 明文不落库，只在"用"的那一刻解密；<br>
 * 2) 列表只返回脱敏串；<br>
 * 3) 解析时必须带 ownerId —— 只做前端过滤就是水平越权。</p>
 */
@Service
public class KeyVaultService {

    private final ApiKeyRepository repository;
    private final AesGcmCipher cipher;

    public KeyVaultService(ApiKeyRepository repository, AesGcmCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public Map<String, Object> create(String ownerId, String projectId, String provider, String apiKey) {
        ApiKeyEntity entity = repository.save(
                new ApiKeyEntity(ownerId, projectId, provider, cipher.encrypt(apiKey), AesGcmCipher.mask(apiKey)));
        return Map.of("id", entity.getId(), "masked", entity.getMasked());
    }

    public List<Map<String, Object>> list(String ownerId) {
        return repository.findByOwnerIdOrderByIdDesc(ownerId).stream()
                .map(e -> Map.<String, Object>of(
                        "id", e.getId(),
                        "provider", e.getProvider(),
                        "projectId", e.getProjectId(),
                        "masked", e.getMasked()))
                .toList();
    }

    /** 带归属校验的读取：别人的 key 直接拒绝 */
    public Map<String, Object> detail(String ownerId, Long id) {
        ApiKeyEntity e = repository.findById(id)
                .orElseThrow(() -> new BizException(4042, "key not found"));
        if (!e.getOwnerId().equals(ownerId)) {
            throw new BizException(4030, "no permission to access this key");
        }
        return Map.of("id", e.getId(), "provider", e.getProvider(), "masked", e.getMasked());
    }

    /** 内部使用：按 id 解密明文（仅供服务端调用，不对外暴露） */
    public String resolve(String idRef) {
        if (idRef == null || idRef.isBlank()) {
            return null;
        }
        long id;
        try {
            id = Long.parseLong(idRef);
        } catch (NumberFormatException e) {
            return null;
        }
        return repository.findById(id)
                .map(e -> cipher.decrypt(e.getCipherText()))
                .orElse(null);
    }
}
