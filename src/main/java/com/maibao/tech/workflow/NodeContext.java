package com.maibao.tech.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/** 节点间传递的上下文：所有值都按名字存取，对应平台里的"变量池"。 */
public class NodeContext {

    private final Map<String, Object> variables = new LinkedHashMap<>();

    public void set(String key, Object value) {
        variables.put(key, value);
    }

    public Object get(String key) {
        return variables.get(key);
    }

    public String getString(String key) {
        Object v = variables.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public int getInt(String key, int defaultValue) {
        Object v = variables.get(key);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }

    public Map<String, Object> snapshot() {
        return Map.copyOf(variables);
    }
}
