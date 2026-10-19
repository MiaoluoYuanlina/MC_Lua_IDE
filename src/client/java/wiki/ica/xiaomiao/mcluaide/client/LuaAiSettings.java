package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.Gson;
import java.net.URI;
import java.util.*;

/** Client-only preferences. Keys and endpoint credentials are never serialized to workspace files. */
final class LuaAiSettings {
    static final Gson JSON = new Gson();
    enum Provider {
        DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash"),
        GPT("GPT / OpenAI", "https://api.openai.com/v1", ""),
        CLAUDE("Claude", "https://api.anthropic.com/v1", "claude-sonnet-4-6"),
        GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta", "");
        final String label, endpoint, model;
        Provider(String label, String endpoint, String model) { this.label = label; this.endpoint = endpoint; this.model = model; }
    }
    record Api(String endpoint, String model, String key) {
        Api { endpoint = Objects.requireNonNullElse(endpoint, "").strip(); model = Objects.requireNonNullElse(model, "").strip(); key = Objects.requireNonNullElse(key, "").strip(); }
        void validate(boolean requireKey) {
            try {
                URI uri = URI.create(endpoint);
                if (uri.getHost() == null || !uri.getScheme().equals("https") || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            } catch (Exception error) { throw new IllegalArgumentException("API 地址必须是 HTTPS 基础地址，不可带密码、查询参数或片段"); }
            if (endpoint.length() > 1024 || model.length() > 120 || !model.matches("[A-Za-z0-9._:/-]*")) throw new IllegalArgumentException("模型 ID 格式不正确");
            if (key.length() > 4096 || key.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("API Key 格式不正确");
            if (requireKey && (key.isBlank() || model.isBlank())) throw new IllegalArgumentException("请先填写模型 ID 和 API Key");
        }
        @Override public String toString() { return "Api[credentials redacted]"; }
    }
    Provider selected = Provider.DEEPSEEK;
    boolean enabled; // Opt-in, never enabled by merely filling a key.
    EnumMap<Provider, Api> apis = new EnumMap<>(Provider.class);
    LuaAiSettings() { for (Provider provider : Provider.values()) apis.put(provider, new Api(provider.endpoint, provider.model, "")); }
    Api api() { return apis.get(selected); }
    LuaAiSettings copy() { LuaAiSettings next = new LuaAiSettings(); next.selected = selected; next.enabled = enabled; next.apis.putAll(apis); return next; }
    static LuaAiSettings decode(String json) {
        LuaAiSettings value = JSON.fromJson(json, LuaAiSettings.class);
        if (value == null || value.selected == null || value.apis == null) throw new IllegalArgumentException("AI 配置损坏");
        for (Provider provider : Provider.values()) {
            Api api = value.apis.get(provider);
            if (api == null) value.apis.put(provider, new Api(provider.endpoint, provider.model, "")); else api.validate(false);
        }
        if (value.enabled) value.api().validate(true);
        return value;
    }
    @Override public String toString() { return "LuaAiSettings[" + selected + ", enabled=" + enabled + ", secrets redacted]"; }
}
