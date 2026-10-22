package org.util.trace.spring;

import java.util.HashMap;
import java.util.Map;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.util.trace.core.config.ConfigSource;

/** {@code trace.*} keys from the Spring {@code Environment}: application.yml, profiles, config server. */
final class SpringEnvironmentConfigSource implements ConfigSource {

    private final Environment environment;

    SpringEnvironmentConfigSource(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String name() {
        return "spring environment";
    }

    @Override
    public String get(String key) {
        return environment.getProperty(key);
    }

    @Override
    public Map<String, String> withPrefix(String prefix) {
        Map<String, String> out = new HashMap<>();
        if (environment instanceof ConfigurableEnvironment) {
            for (PropertySource<?> source : ((ConfigurableEnvironment) environment).getPropertySources()) {
                if (source instanceof EnumerablePropertySource) {
                    for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                        if (name.startsWith(prefix) && !out.containsKey(name)) {
                            String value = environment.getProperty(name);
                            if (value != null) {
                                out.put(name, value);
                            }
                        }
                    }
                }
            }
        }
        return out;
    }
}
