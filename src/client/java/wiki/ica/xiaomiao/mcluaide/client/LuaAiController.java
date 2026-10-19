package wiki.ica.xiaomiao.mcluaide.client;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.Pattern;

/** Client-thread scheduling; every model/file round runs off the game thread. */
final class LuaAiController {
    interface Host {
        LuaAiAgent.Problem currentProblem();
        LuaAiAgent.Workspace workspace();
        void dispatch(Runnable action);
        void log(String text);
        default LuaAiAnalysisHistory analysisHistory(LuaAiAgent.Problem problem) { return null; }
    }
    private static final ExecutorService WORKER = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("lua-ai-", 0).factory());
    private static long lastAutomaticRequest;
    private final Host host;
    private final LuaAiSecretStore store;
    private LuaAiSettings settings;
    private String status = "自动报错分析默认关闭";
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final Map<String, List<LuaAiAgent.Mark>> marks = new HashMap<>();
    private final Map<String, String> verifiedSources = new HashMap<>();
    private LuaAiAgent.Problem pending;
    private long due;
    private volatile long generation;
    private Future<?> task;
    private boolean busy;
    private LuaAiAnalysisHistory inFlightHistory;
    private String inFlightKey;
    private final LuaAiAgent.Transport transport;
    LuaAiController(Host host) { this(host, new LuaAiSecretStore(), null); }
    LuaAiController(Host host, LuaAiSecretStore store, LuaAiAgent.Transport transport) { this.host = host; this.store = store; this.transport = transport; }
    LuaAiSettings settings() {
        if (settings == null) {
            try { settings = store.load(); status = settings.enabled ? "自动报错分析已启用" : "自动报错分析已关闭"; }
            catch (Exception | LinkageError error) { settings = new LuaAiSettings(); status = "未读取 AI 密钥：注册表或 DPAPI 不可用/配置损坏；保持关闭"; }
        }
        return settings.copy();
    }
    void configure(LuaAiSettings next) throws Exception {
        store.save(next); cancel(false); settings = next.copy(); seen.clear();
        status = next.enabled ? "已保存并开启自动分析（会发送报错和相关代码）" : "已保存；自动分析关闭，不会自动发送代码";
        // Enabling the switch must not send existing editor diagnostics. Only execution events
        // feed automatic analysis; currentProblem is exclusively for the explicit manual button.
    }
    String status() { return status; }
    boolean busy() { return busy; }
    void runtimeError(LuaAiAgent.Problem problem) {
        if (!settings().enabled || problem == null || problem.error().isBlank()) { pending = null; return; }
        String key = fingerprint(problem);
        LuaAiAnalysisHistory history = host.analysisHistory(problem);
        if (history != null ? history.contains(key) : seen.contains(key)) return;
        pending = problem; due = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    }
    void tick() {
        if (pending != null && !busy && System.nanoTime() >= due
                && System.nanoTime() - lastAutomaticRequest > TimeUnit.SECONDS.toNanos(30)) {
            LuaAiAgent.Problem next = pending; pending = null; start(next, false);
        }
    }
    void manual() {
        var problem = host.currentProblem();
        if (problem == null || problem.error().isBlank()) { status = "当前文件没有可分析的报错"; return; }
        start(problem, true);
    }
    private static String fingerprint(LuaAiAgent.Problem problem) { return problem.path() + LuaAiAgent.hash(problem.source()) + LuaAiAgent.hash(problem.error()); }
    private void start(LuaAiAgent.Problem problem, boolean manual) {
        if (busy) { status = "正在分析，可先取消"; return; }
        var config = settings();
        if (!manual && !config.enabled) return;
        try { config.api().validate(true); } catch (Exception error) { status = error.getMessage(); return; }
        String key = fingerprint(problem);
        LuaAiAnalysisHistory history = host.analysisHistory(problem);
        if (!manual && (history != null ? history.contains(key) : seen.contains(key))) return;
        LuaAiAgent.Workspace workspace = host.workspace();
        if (history != null && !history.claim(key) && !manual) return;
        inFlightHistory = history; inFlightKey = key;
        seen.add(key); while (seen.size() > 128) seen.remove(seen.iterator().next());
        if (!manual) lastAutomaticRequest = System.nanoTime();
        long id = ++generation;
        busy = true; status = "正在分析 " + problem.path() + " · 文件只读";
        host.log("[AI · " + config.selected.label + "] 开始分析 " + problem.path());
        task = WORKER.submit(() -> {
            try {
                var result = new LuaAiAgent(workspace, () -> id != generation, transport).analyze(config.selected, config.api(), problem);
                host.dispatch(() -> {
                    if (id != generation) return;
                    inFlightHistory = null; inFlightKey = null;
                    busy = false; marks.clear(); verifiedSources.clear();
                    for (var mark : result.marks()) marks.computeIfAbsent(mark.path(), ignored -> new ArrayList<>()).add(mark);
                    status = "分析完成 · " + result.marks().size() + " 处标注 · " + result.rounds() + " 轮 / " + result.calls() + " 次工具调用";
                    host.log("[AI 分析 · " + problem.path() + "]\n" + (result.reply().isBlank() ? "AI 已添加代码标注，但没有返回文字解释。" : LuaAiAgent.redact(result.reply())));
                });
            } catch (Exception error) {
                host.dispatch(() -> {
                    if (id != generation) return;
                    inFlightHistory = null; inFlightKey = null;
                    busy = false;
                    // Network exceptions/remote response bodies can echo URL credentials or source: never print them.
                    String reason = error instanceof java.io.IOException && error.getMessage() != null && error.getMessage().startsWith("AI ")
                            ? error.getMessage() : error instanceof IllegalArgumentException ? error.getMessage()
                            : "请求失败或超时，请检查网络、模型 ID、额度及工具调用支持";
                    status = "AI 分析失败：" + reason; host.log("[AI] " + status);
                });
            }
        });
    }
    void edited(String path) {
        marks.remove(path);
        verifiedSources.remove(path);
        if (pending != null && pending.path().equals(path)) pending = null;
        // The analysis may have read a now-edited dependency; invalidate the whole in-flight snapshot.
        if (busy) cancel(false);
    }
    void clearMarks() { marks.clear(); verifiedSources.clear(); status = "已清除全部 AI 标注"; }
    List<LuaAiAgent.Mark> marks(String path, String source) {
        var list = marks.get(path); if (list == null) return List.of();
        if (verifiedSources.get(path) == source) return list;
        String hash = LuaAiAgent.hash(source);
        if (list.stream().anyMatch(mark -> !mark.hash().equals(hash))) { marks.remove(path); return List.of(); }
        verifiedSources.put(path, source); return list;
    }
    void cancel(boolean report) {
        generation++; pending = null;
        if (task != null) task.cancel(true);
        if (inFlightKey != null) {
            seen.remove(inFlightKey);
            if (inFlightHistory != null) inFlightHistory.release(inFlightKey);
        }
        inFlightHistory = null; inFlightKey = null; task = null; busy = false;
        if (report) status = "已取消分析；已发送的数据无法撤回";
    }
    static List<Integer> runtimeLines(String error) {
        var matcher = Pattern.compile("(?:\\.lua:|:)(\\d+)(?::|\\s)").matcher(error); List<Integer> lines = new ArrayList<>();
        while (matcher.find() && lines.size() < 12) try { lines.add(Integer.parseInt(matcher.group(1))); } catch (NumberFormatException ignored) { }
        return lines;
    }
}
