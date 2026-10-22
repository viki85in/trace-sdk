package org.util.trace.bench;

import java.util.HashMap;
import java.util.Map;

/** --key=value arguments. */
final class Args {

    private final Map<String, String> values = new HashMap<>();

    Args(String[] args, int from) {
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--") && a.contains("=")) {
                int eq = a.indexOf('=');
                values.put(a.substring(2, eq), a.substring(eq + 1));
            }
        }
    }

    String str(String key, String def) {
        return values.getOrDefault(key, def);
    }

    int num(String key, int def) {
        return values.containsKey(key) ? Integer.parseInt(values.get(key)) : def;
    }

    double dbl(String key, double def) {
        return values.containsKey(key) ? Double.parseDouble(values.get(key)) : def;
    }

    boolean has(String key) {
        return values.containsKey(key);
    }
}
