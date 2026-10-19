package wiki.ica.xiaomiao.mcluaide.client;

import java.util.LinkedHashSet;

/** Scoped to a single execution, so closing the IDE does not replay completed AI requests. */
final class LuaAiAnalysisHistory {
    private final LinkedHashSet<String> requested = new LinkedHashSet<>();

    synchronized boolean contains(String key) { return requested.contains(key); }

    synchronized boolean claim(String key) {
        if (!requested.add(key)) return false;
        while (requested.size() > 128) requested.remove(requested.iterator().next());
        return true;
    }

    synchronized void release(String key) { requested.remove(key); }
}
