package com.maibao.tech.adapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 适配器注册表：把"按 model 选适配器"的 if-else 收敛成一次查表。
 *
 * <p>新增一个厂商 = 新增一个 @Component 实现，这里不用改。</p>
 */
@Component
public class AdapterRegistry {

    private final Map<String, AiProviderAdapter> adapters = new HashMap<>();
    private final AiProviderAdapter fallback;

    public AdapterRegistry(List<AiProviderAdapter> all) {
        for (AiProviderAdapter a : all) {
            adapters.put(a.name(), a);
        }
        this.fallback = adapters.get("mock");
    }

    /** model 形如 "openai:gpt-4o-mini" / "mock:demo"，前缀决定走哪个适配器 */
    public AiProviderAdapter resolve(String model) {
        String prefix = model == null ? "" : model.split(":", 2)[0];
        return adapters.getOrDefault(prefix, fallback);
    }
}
