package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.*;
import com.sun.jna.*;
import com.sun.jna.ptr.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synthetic keys/responses only: never contacts a provider or reads real saved credentials. */
public final class LuaAiSmoke {
    private static int checks;
    static final String MAIN = "lua/main.lua", MODULE = "lua/lib/math.lua";
    static final String SOURCE = "local other = require('lib/math')\nprint(missing)\n";
    static JsonObject obj(Object... values) { JsonObject result = new JsonObject(); for (int i = 0; i < values.length; i += 2) result.add((String) values[i], LuaAiAgent.JSON.toJsonTree(values[i + 1])); return result; }
    static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static LuaAiAgent.Workspace workspace() {
        return new LuaAiAgent.Workspace() {
            public CompletableFuture<List<String>> list() { return CompletableFuture.completedFuture(List.of(MAIN, MODULE, "lua/../secrets.lua", "config/api.properties", "resourcepacks/a/image.png")); }
            public CompletableFuture<String> read(String path) { if (path.equals(MODULE)) return CompletableFuture.completedFuture("return { x = 3 }\n"); return CompletableFuture.failedFuture(new AssertionError("Unexpected file read")); }
            public String api(String symbol) { return "作用：查看世界\n返回：表"; }
        };
    }
    static LuaAiAgent.Transport mock() {
        AtomicInteger rounds = new AtomicInteger();
        return (provider, api, request) -> {
            int round = (rounds.getAndIncrement() % 4) + 1;
            require(!request.toString().contains(api.key()), "API key leaked into request body");
            require(request.has(provider == LuaAiSettings.Provider.GPT ? "input" : provider == LuaAiSettings.Provider.GEMINI ? "contents" : "messages"), "Wrong provider history field");
            List<JsonObject> calls = switch (round) {
                case 1 -> List.of(obj("name", "list_files", "args", obj("query", "", "offset", 0)), obj("name", "search_file", "args", obj("path", MAIN, "query", "missing")), obj("name", "read_api", "args", obj("symbol", "MC.World.info")));
                case 2 -> List.of(obj("name", "read_file", "args", obj("path", MODULE, "startLine", 1, "endLine", 2)));
                case 3 -> List.of(obj("name", "annotate_code", "args", obj("marks", List.of(
                        obj("path", MAIN, "startLine", 2, "startColumn", 7, "endLine", 2, "endColumn", 14, "message", "missing 未定义", "severity", "error"),
                        obj("path", MODULE, "startLine", 1, "startColumn", 1, "endLine", 1, "endColumn", 7, "message", "模块返回值说明", "severity", "info")))));
                default -> List.of();
            };
            if (round > 1) {
                String text = request.toString(); require(text.contains("total") || text.contains("sourceHash"), "Tool results were not fed back");
                require(!text.contains("config/api.properties") && !text.contains("image.png"), "File list leaked disallowed paths");
            }
            JsonArray nativeCalls = new JsonArray();
            for (int i = 0; i < calls.size(); i++) {
                var call = calls.get(i); String id = "call_" + round + "_" + i;
                nativeCalls.add(switch (provider) {
                    case GPT -> obj("type", "function_call", "call_id", id, "name", call.get("name"), "arguments", call.get("args").toString());
                    case DEEPSEEK -> obj("id", id, "type", "function", "function", obj("name", call.get("name"), "arguments", call.get("args").toString()));
                    case CLAUDE -> obj("type", "tool_use", "id", id, "name", call.get("name"), "input", call.get("args"));
                    case GEMINI -> obj("functionCall", obj("id", id, "name", call.get("name"), "args", call.get("args")), "thoughtSignature", "fixture_signature");
                });
            }
            String finalText = calls.isEmpty() ? "missing 未定义，请先声明；具体建议见标注。" : "";
            return switch (provider) {
                case GPT -> { if (round == 1) nativeCalls.add(obj("type", "reasoning", "id", "rs_fixture", "encrypted_content", "fixture_reasoning", "summary", List.of())); if (!finalText.isEmpty()) nativeCalls.add(obj("type", "message", "role", "assistant", "content", List.of(obj("type", "output_text", "text", finalText))));
                    if (round > 1) require(request.toString().contains("fixture_reasoning"), "Responses reasoning items were lost"); require(!request.get("store").getAsBoolean(), "OpenAI storage was not disabled"); yield obj("output", nativeCalls); }
                case DEEPSEEK -> obj("choices", List.of(obj("message", obj("role", "assistant", "content", finalText, "tool_calls", nativeCalls, "reasoning_content", "fixture_reasoning"))));
                case CLAUDE -> { if (!finalText.isEmpty()) nativeCalls.add(obj("type", "text", "text", finalText)); yield obj("content", nativeCalls, "stop_reason", calls.isEmpty() ? "end_turn" : "tool_use"); }
                case GEMINI -> { if (round > 1) require(request.toString().contains("fixture_signature"), "Gemini thought signature lost"); if (!finalText.isEmpty()) nativeCalls.add(obj("text", finalText)); yield obj("candidates", List.of(obj("content", obj("role", "model", "parts", nativeCalls)))); }
            };
        };
    }
    public static void main(String[] args) throws Exception {
        require(!new LuaAiSettings().enabled, "AI enabled by default");
        for (var provider : LuaAiSettings.Provider.values()) {
            var agent = new LuaAiAgent(workspace(), () -> false, mock());
            var result = agent.analyze(provider, new LuaAiSettings.Api(provider.endpoint, "fixture_model", "fake_private_test_key"), new LuaAiAgent.Problem(MAIN, SOURCE, "missing 未定义", List.of(2)));
            require(result.rounds() == 4 && result.calls() == 5 && result.marks().size() == 2 && result.reply().contains("未定义"), "Tool loop/final text/multi-file marks failed");
            var mark = result.marks().getFirst(); require(SOURCE.substring(mark.start(), mark.end()).equals("missing") && mark.hash().equals(LuaAiAgent.hash(SOURCE)), "Line/column annotation mapping failed");
        }
        require(LuaAiAgent.offset("中😀\nend", 2, 1) == 4, "UTF-16 line offsets failed");
        String sensitive = "local apiKey = 'sk-testABCDEFGHIJKLMNOPQRSTUVWXYZ'\nAuthorization: Bearer privateToken\n";
        String redacted = LuaAiAgent.redact(sensitive); require(!redacted.contains("sk-test") && !redacted.contains("privateToken") && sensitive.length() == redacted.length(), "Secrets redaction changed columns or leaked key");
        for (String bad : List.of("lua/../a.lua", "lua//a.lua", "lua/C:/secret.lua", "lua/a\\b.lua", "config/a.lua", "lua/a.png", "resourcepacks/./a.json")) require(!LuaAiAgent.textPath(bad), "Invalid path accepted");
        for (String endpoint : List.of("http://example.com", "https://key@host/v1", "https://host/v1?key=x", "https://host/#secret")) {
            try { new LuaAiSettings.Api(endpoint, "fixture", "fake").validate(true); throw new AssertionError("Insecure endpoint accepted"); } catch (IllegalArgumentException expected) { checks++; }
        }
        var agent = new LuaAiAgent(workspace(), () -> false);
        try { agent.tool("write_file", new JsonObject()); throw new AssertionError("Write tool accepted"); } catch (IllegalArgumentException expected) { checks++; }
        try { agent.tool("read_file", obj("path", "lua/no_permission.lua", "startLine", 1, "endLine", 2)); throw new AssertionError("Unlisted file accepted"); } catch (IllegalArgumentException expected) { checks++; }
        try { agent.tool("read_file", obj("path", MODULE, "startLine", 1.5, "endLine", 2)); throw new AssertionError("Noninteger line accepted"); } catch (IllegalArgumentException expected) { checks++; }
        try { LuaAiAgent.offset(SOURCE, 99, 1); throw new AssertionError("Invalid annotation line accepted"); } catch (IllegalArgumentException expected) { checks++; }
        try { agent.tool("read_file", obj("path", MODULE, "startLine", 1, "endLine", 201)); throw new AssertionError("Read line bound ignored"); } catch (IllegalArgumentException expected) { checks++; }
        try { agent.tool("annotate_code", obj("marks", List.of(obj("path", MODULE, "startLine", 1, "startColumn", 1, "endLine", 1, "endColumn", 99, "message", "bad", "severity", "error")))); throw new AssertionError("Out of range mark accepted"); } catch (IllegalArgumentException expected) { checks++; }
        String huge = "local x = '" + "x".repeat(100_000) + "'\n";
        require(LuaAiAgent.context(huge, List.of(1)).length() <= 16000, "Initial long-line context unbounded");
        checkExecutionHistory();
        try { new LuaAiAgent(workspace(), () -> true, mock()).analyze(LuaAiSettings.Provider.GPT, new LuaAiSettings.Api("https://api.openai.com/v1", "fixture", "fake"), new LuaAiAgent.Problem(MAIN, SOURCE, "error", List.of(2))); throw new AssertionError("Cancellation ignored"); } catch (CancellationException expected) { checks++; }
        if (LuaAiSecretStore.supported()) registryAndController();
        System.out.println("AI smoke passed: " + checks + " checks; four native tool loops, read-only paths, bounds, multi-file marks, redaction, cancellation and DPAPI registry round trip; zero real API calls");
    }
    private static void checkExecutionHistory() throws Exception {
        var constructor = ScriptManager.ScriptExecution.class.getDeclaredConstructor(long.class, String.class, String.class, String.class, java.util.function.Consumer.class, java.util.function.Consumer.class);
        constructor.setAccessible(true);
        var execution = constructor.newInstance(1L, "fixture", "fixture.lua", SOURCE, (java.util.function.Consumer<String>)value -> {}, (java.util.function.Consumer<ScriptManager.ExecutionResult>)value -> {});
        require(execution.originalSource().equals(SOURCE), "Background execution source snapshot lost");
        String warning = "⚠ 停止回调 #3 失败：fixture.lua:2 bad argument";
        execution.appendIdeMessage(warning); require(execution.reportedError() == null, "AI quoted error triggered recursive analysis");
        var emit = ScriptManager.ScriptExecution.class.getDeclaredMethod("emitOutput", String.class); emit.setAccessible(true); emit.invoke(execution, warning);
        require(execution.reportedError().sequence() == 1 && execution.reportedError().message().equals(warning), "Cleanup callback error not captured");
        execution.appendIdeMessage("[AI] 原因解释"); emit.invoke(execution, "later output");
        require(execution.outputLines().stream().anyMatch(line -> line.contains("原因解释")) && execution.reportedError().sequence() == 1, "Log sync erased AI output or created extra error");
        require(LuaAiController.runtimeLines(warning).equals(List.of(2)), "Runtime error line parsing failed");
        emit.invoke(execution, "⚠ MC.Block.get：区块未加载；已跳过本次调用");
        require(execution.reportedError().sequence() == 1, "Recoverable warning became an AI error");
        var last = ScriptManager.class.getDeclaredField("lastExecution"); last.setAccessible(true);
        last.set(null, execution);
        var finish = ScriptManager.ScriptExecution.class.getDeclaredMethod("finish", ScriptManager.ExecutionResult.class); finish.setAccessible(true);
        finish.invoke(execution, new ScriptManager.ExecutionResult(false, "fixture.lua:2 failure"));
        require(ScriptManager.lastExecution() == execution && !execution.isRunning()
                && execution.outputLines().stream().anyMatch(line -> line.contains("failure")), "Finished run/log lost on reopen");
        var newer = constructor.newInstance(2L, "newer", "newer.lua", "print(1)", (java.util.function.Consumer<String>)value -> {}, (java.util.function.Consumer<ScriptManager.ExecutionResult>)value -> {});
        last.set(null, newer);
        finish.invoke(execution, new ScriptManager.ExecutionResult(true, null));
        require(ScriptManager.lastExecution() == newer, "Old completion replaced newer run history");
        last.set(null, null);
        LuaLogHud.disconnected();
    }
    private static void registryAndController() throws Exception {
        String path = LuaAiSecretStore.KEY + "_Test_" + UUID.randomUUID().toString().replace("-", ""); var store = new LuaAiSecretStore(path);
        var preferences = new LuaAiSettings(); preferences.apis.put(preferences.selected, new LuaAiSettings.Api(preferences.selected.endpoint, "fixture", "fake_registry_secret_123456"));
        try {
            require(!store.load().enabled, "New registry config enabled"); store.save(preferences);
            var loaded = store.load(); require(loaded.api().key().equals(preferences.api().key()) && !loaded.enabled, "DPAPI registry round trip failed");
            var nativeReg = Native.load("Advapi32", LuaAiSecretStore.Registry.class); PointerByReference handle = new PointerByReference();
            require(nativeReg.RegOpenKeyExW(new Pointer((long) (int) 0x80000001), new WString(path), 0, 1, handle) == 0, "Cannot inspect dedicated registry fixture");
            try { IntByReference size = new IntByReference(), type = new IntByReference(); nativeReg.RegQueryValueExW(handle.getValue(), new WString("EncryptedConfigurationV1"), null, type, null, size); byte[] bytes = new byte[size.getValue()];
                nativeReg.RegQueryValueExW(handle.getValue(), new WString("EncryptedConfigurationV1"), null, type, bytes, size);
                require(type.getValue() == 3 && !new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("fake_registry_secret"), "Plaintext key in registry");
            } finally { nativeReg.RegCloseKey(handle.getValue()); }
            var problem = new LuaAiAgent.Problem(MAIN, SOURCE, "missing 未定义", List.of(2)); var dispatch = new ConcurrentLinkedQueue<Runnable>(); List<String> logs = new ArrayList<>();
            var history = new LuaAiAnalysisHistory();
            var currentHistory = new AtomicReference<>(history);
            var host = new LuaAiController.Host() {
                public LuaAiAgent.Problem currentProblem() { return problem; } public LuaAiAgent.Workspace workspace() { return LuaAiSmoke.workspace(); }
                public void dispatch(Runnable action) { dispatch.add(action); } public void log(String text) { logs.add(text); }
                public LuaAiAnalysisHistory analysisHistory(LuaAiAgent.Problem p) { return currentHistory.get(); }
            };
            var controller = new LuaAiController(host, store, mock());
            controller.runtimeError(problem); controller.tick(); require(!controller.busy() && logs.isEmpty(), "Disabled automatic AI made a request");
            var enabled = controller.settings(); enabled.enabled = true; controller.configure(enabled);
            controller.tick(); require(!controller.busy() && logs.isEmpty(), "Enabling AI analyzed existing editor diagnostics");
            var pending = LuaAiController.class.getDeclaredField("pending"); pending.setAccessible(true);
            require(pending.get(controller) == null, "Static error was queued on configuration save");
            controller.manual(); long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (controller.busy() && System.nanoTime() < end) { Runnable action; while ((action = dispatch.poll()) != null) action.run(); Thread.sleep(10); }
            require(!controller.busy() && logs.stream().anyMatch(x -> x.contains("AI 分析")), "Manual analysis did not finish/log");
            require(controller.marks(MAIN, SOURCE).size() == 1 && controller.marks(MODULE, "return { x = 3 }\n").size() == 1, "Multiple file marks lost");
            require(controller.marks(MAIN, SOURCE + "\n").isEmpty(), "Stale code annotation retained"); controller.clearMarks(); require(controller.marks(MODULE, "return { x = 3 }\n").isEmpty(), "Clear-all left marks");
            controller.runtimeError(problem);
            require(pending.get(controller) == null, "Already analyzed error was not deduplicated");
            var runtimeProblem = new LuaAiAgent.Problem(MAIN, SOURCE, "fixture.lua:2 attempt to call nil", List.of(2));
            controller.runtimeError(runtimeProblem); require(pending.get(controller) == runtimeProblem, "Actual execution failure not queued");
            var due = LuaAiController.class.getDeclaredField("due"); due.setAccessible(true); due.setLong(controller, 0);
            controller.tick(); require(controller.busy(), "Execution failure did not start automatic analysis");
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (controller.busy() && System.nanoTime() < end) { Runnable action; while ((action = dispatch.poll()) != null) action.run(); Thread.sleep(10); }
            require(!controller.busy() && logs.stream().filter(x -> x.contains("[AI 分析")).count() == 2, "Automatic execution analysis did not finish");
            controller.cancel(false);
            var reopened = new LuaAiController(host, store, mock());
            reopened.runtimeError(runtimeProblem);
            require(pending.get(reopened) == null, "Reopened IDE reanalyzed completed runtime error");
            var unhandled = new LuaAiAgent.Problem(MAIN, SOURCE, "fixture.lua:3 new runtime failure", List.of(3));
            reopened.runtimeError(unhandled);
            require(pending.get(reopened) == unhandled, "Unanalyzed background error was skipped on reopen");
            due.setLong(reopened, 0);
            var cooldown = LuaAiController.class.getDeclaredField("lastAutomaticRequest"); cooldown.setAccessible(true); cooldown.setLong(null, 0);
            reopened.tick(); require(reopened.busy(), "Background error did not start analysis");
            reopened.cancel(false);
            var retry = new LuaAiController(host, store, mock()); retry.runtimeError(unhandled);
            require(pending.get(retry) == unhandled, "Cancelled analysis could not be resumed on reopening");
            retry.cancel(false);
            currentHistory.set(new LuaAiAnalysisHistory());
            controller.runtimeError(runtimeProblem);
            require(pending.get(controller) == runtimeProblem, "New run's identical runtime error was blocked by old controller history");
            controller.cancel(false);
            var anotherRun = new LuaAiAnalysisHistory();
            require(anotherRun.claim("same-error") && !anotherRun.claim("same-error"), "Per-run receipt did not deduplicate");
            anotherRun.release("same-error"); require(anotherRun.claim("same-error"), "Receipt release failed");
            for (int i = 0; i < 1000; i++) anotherRun.claim("error-" + i);
            require(!anotherRun.contains("error-0") && anotherRun.contains("error-999"), "Receipt history is unbounded or lost latest errors");
        } finally { store.deleteTestKey(); }
    }
}
