package com.core.agent;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具注册中心：管理所有可用的 Tool。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public Collection<Tool> all() {
        return tools.values();
    }
}
