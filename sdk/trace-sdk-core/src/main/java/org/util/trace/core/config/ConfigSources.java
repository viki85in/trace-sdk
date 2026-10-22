package org.util.trace.core.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

final class ConfigSources {

    private ConfigSources() {
    }

    static Map<String, String> prefixed(Properties properties, String prefix) {
        Map<String, String> out = new HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(prefix)) {
                out.put(name, properties.getProperty(name));
            }
        }
        return out;
    }
}
