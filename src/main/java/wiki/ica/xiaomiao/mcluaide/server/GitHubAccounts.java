package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import wiki.ica.xiaomiao.mcluaide.network.LuaGit;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Device-flow credentials are server-memory-only and bound to the requesting administrator. */
final class GitHubAccounts {
	/** Public application identifier, not a password, secret or shared user credential. */
	static final String DEFAULT_CLIENT_ID = "Ov23li9ALF0BE5QLuRIs";
	private static final String DEVICE = "https://github.com/login/device";
	private final Path root;
	private final Transport transport;
	private final LongSupplier clock;
	private final String environmentClientId;
	private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, java.util.concurrent.atomic.AtomicLong> generations = new ConcurrentHashMap<>();
	private static final class Session {
		String clientId = "", deviceCode = "", userCode = "", token = "", login = "";
		long expiresAt, nextPoll, tokenExpiresAt;
		int interval = 5;
	}
	interface Transport { JsonElement request(String method, String url, String token, String body, boolean form) throws IOException; }
	GitHubAccounts(Path root) { this(root, new WebTransport(), System::currentTimeMillis, System.getenv("MC_LUA_IDE_GITHUB_CLIENT_ID")); }
	GitHubAccounts(Path root, Transport transport, LongSupplier clock, String environmentClientId) {
		this.root = root.toAbsolutePath().normalize(); this.transport = transport; this.clock = clock;
		this.environmentClientId = environmentClientId == null ? "" : environmentClientId.strip();
	}
	String clientId() throws IOException {
		if (!environmentClientId.isEmpty()) { validateClientId(environmentClientId); return environmentClientId; }
		Path file = root.resolve("github-login.properties"); GitWorkspaceRepository.assertSafe(root, file);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return DEFAULT_CLIENT_ID;
		Properties settings = new Properties();
		try (var input = Files.newInputStream(file)) { settings.load(input); }
		String id = settings.getProperty("clientId", "").strip();
		if (id.isEmpty()) return DEFAULT_CLIENT_ID;
		validateClientId(id); return id;
	}
	synchronized void saveClientId(String id) throws IOException {
		if (!environmentClientId.isEmpty()) throw new IOException("Client ID 由服务器环境变量管理，不能在此修改");
		if (!id.isEmpty()) validateClientId(id);
		Properties settings = new Properties(); settings.setProperty("clientId", id);
		var bytes = new java.io.ByteArrayOutputStream(); settings.store(bytes, "Public GitHub OAuth App client ID; no credentials");
		GitWorkspaceRepository.atomicWrite(root, root.resolve("github-login.properties"), bytes.toByteArray());
		clear();
	}
	static void validateClientId(String id) throws IOException {
		if (id == null || !id.matches("[A-Za-z0-9_.-]{10,100}") || id.matches("(?i)(gh[pousr]_|github_pat_).*"))
			throw new IOException("请输入 OAuth App 的公开 Client ID，不要填写 Client Secret 或访问令牌");
	}
	LuaGit.Account account(UUID owner) throws IOException {
		String id = clientId(); Session session = sessions.get(owner);
		if (session == null) return signedOut(id, "先选择使用浏览器登录 GitHub");
		synchronized (session) {
			if ((!session.token.isEmpty() && session.tokenExpiresAt > 0 && clock.getAsLong() >= session.tokenExpiresAt)
					|| (session.token.isEmpty() && clock.getAsLong() >= session.expiresAt)) {
				sessions.remove(owner, session); return signedOut(id, "登录已过期，请重新通过浏览器登录");
			}
			return view(session, session.token.isEmpty() ? "请在浏览器中输入一次性验证码并授权" : "已登录 " + session.login);
		}
	}
	private LuaGit.Account signedOut(String id, String notice) {
		return new LuaGit.Account(id.isEmpty() ? LuaGit.LoginPhase.CONFIGURE : LuaGit.LoginPhase.SIGNED_OUT,
				id, "", "", "", 0, 5, id.isEmpty() ? "服务器尚未配置 OAuth App Client ID（需启用 Device Flow）" : notice);
	}
	private LuaGit.Account view(Session session, String notice) {
		boolean connected = !session.token.isEmpty();
		return new LuaGit.Account(connected ? LuaGit.LoginPhase.CONNECTED : LuaGit.LoginPhase.PENDING, session.clientId,
				session.login, connected ? "" : session.userCode, connected ? "" : DEVICE,
				connected ? session.tokenExpiresAt : session.expiresAt, session.interval, notice);
	}
	LuaGit.Account begin(UUID owner) throws IOException {
		return begin(owner, generation(owner));
	}
	long generation(UUID owner) { return generations.computeIfAbsent(owner, ignored -> new java.util.concurrent.atomic.AtomicLong()).get(); }
	void checkGeneration(UUID owner, long generation) throws IOException { if (generation(owner) != generation) throw new IOException("登录请求已取消或玩家已离开服务器"); }
	LuaGit.Account begin(UUID owner, long generation) throws IOException {
		checkGeneration(owner, generation);
		String id = clientId(); if (id.isEmpty()) throw new IOException("请先配置 GitHub OAuth App Client ID，并启用 Device Flow");
		Session session = new Session(); session.clientId = id; session.expiresAt = clock.getAsLong() + 900000;
		// Reserve before HTTP so cancellation/disconnection cannot resurrect an in-flight login.
		Session prior = sessions.putIfAbsent(owner, session);
		if (prior != null) {
			LuaGit.Account current = account(owner);
			if (current.phase() == LuaGit.LoginPhase.PENDING || current.phase() == LuaGit.LoginPhase.CONNECTED) return current;
			return begin(owner, generation);
		}
		synchronized (session) {
			try {
				checkGeneration(owner, generation);
				JsonObject json = object(transport.request("POST", DEVICE + "/code", "", form("client_id", id, "scope", "repo"), true));
				oauthError(json);
				session.deviceCode = value(json, "device_code", 200); session.userCode = value(json, "user_code", 24);
				if (!DEVICE.equals(value(json, "verification_uri", 200)) || !session.userCode.matches("[A-Z0-9]{4}-[A-Z0-9]{4}"))
					throw new IOException("GitHub 登录返回了不安全的验证地址或验证码");
				int expires = integer(json, "expires_in", 900); session.interval = Math.clamp(integer(json, "interval", 5), 5, 60);
				session.expiresAt = clock.getAsLong() + Math.clamp(expires, 1, 900) * 1000L;
				session.nextPoll = clock.getAsLong() + session.interval * 1000L;
				checkGeneration(owner, generation);
				if (sessions.get(owner) != session) throw new IOException("登录已取消");
				return view(session, "验证码已生成，请在 GitHub 浏览器页面完成授权");
			} catch (IOException | RuntimeException error) { sessions.remove(owner, session); throw error; }
		}
	}
	LuaGit.Account poll(UUID owner) throws IOException {
		Session session = sessions.get(owner); if (session == null) return account(owner);
		synchronized (session) {
			if (!session.token.isEmpty() || clock.getAsLong() >= session.expiresAt) return account(owner);
			if (clock.getAsLong() < session.nextPoll) return view(session, "等待浏览器授权…");
			session.nextPoll = clock.getAsLong() + session.interval * 1000L;
			JsonObject json = object(transport.request("POST", "https://github.com/login/oauth/access_token", "",
					form("client_id", session.clientId, "device_code", session.deviceCode,
							"grant_type", "urn:ietf:params:oauth:grant-type:device_code"), true));
			String error = optional(json, "error", 100);
			if (error.equals("authorization_pending")) return view(session, "等待浏览器授权…");
			if (error.equals("slow_down")) {
				session.interval = Math.min(120, session.interval + 5); session.nextPoll = clock.getAsLong() + session.interval * 1000L;
				return view(session, "GitHub 要求降低登录轮询频率，正在等待…");
			}
			if (!error.isEmpty()) { sessions.remove(owner, session); oauthError(json); }
			String token = value(json, "access_token", 2048);
			if (!"bearer".equalsIgnoreCase(value(json, "token_type", 20)) || token.chars().anyMatch(Character::isISOControl))
				throw new IOException("GitHub 返回的认证格式无效");
			if (java.util.Arrays.stream(optional(json, "scope", 500).split("[, ]+")).noneMatch("repo"::equals)) {
				sessions.remove(owner, session); throw new IOException("GitHub 未授予 repo 权限，请重新登录并确认授权");
			}
			JsonObject user = object(transport.request("GET", "https://api.github.com/user", token, "", false));
			String login = value(user, "login", 39);
			if (!login.matches("[A-Za-z0-9-]{1,39}")) throw new IOException("GitHub 账号信息无效");
			if (sessions.get(owner) != session) throw new IOException("登录已取消");
			session.token = token; session.login = login; session.deviceCode = ""; session.userCode = "";
			int expires = integer(json, "expires_in", 0);
			session.tokenExpiresAt = expires > 0 ? clock.getAsLong() + Math.min(expires, 86400) * 1000L : 0;
			return view(session, "已登录 " + login + "；请选择已有仓库或新建仓库");
		}
	}
	String token(UUID owner) throws IOException {
		if (owner == null) return "";
		LuaGit.Account current = account(owner);
		Session session = sessions.get(owner);
		if (current.phase() != LuaGit.LoginPhase.CONNECTED || session == null) return "";
		synchronized (session) { return sessions.get(owner) == session ? session.token : ""; }
	}
	private String requiredToken(UUID owner) throws IOException {
		String token = token(owner); if (token.isEmpty()) throw new IOException("请先通过浏览器登录 GitHub"); return token;
	}
	void logout(UUID owner) { generations.computeIfAbsent(owner, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet(); sessions.remove(owner); }
	void clear() { generations.values().forEach(java.util.concurrent.atomic.AtomicLong::incrementAndGet); sessions.clear(); }
	LuaGit.Repositories repositories(UUID owner, int page) throws IOException {
		if (page < 1 || page > 1000) throw new IOException("仓库页码无效");
		JsonElement json = transport.request("GET", "https://api.github.com/user/repos?affiliation=owner,collaborator,organization_member&sort=updated&per_page=30&page=" + page,
				requiredToken(owner), "", false);
		if (!json.isJsonArray() || json.getAsJsonArray().size() > 30) throw new IOException("GitHub 仓库列表格式无效");
		ArrayList<LuaGit.Repository> result = new ArrayList<>();
		for (JsonElement entry : json.getAsJsonArray()) {
			JsonObject repo = object(entry);
			if (writable(repo)) result.add(repository(repo));
		}
		return new LuaGit.Repositories(result, page, json.getAsJsonArray().size() == 30);
	}
	LuaGit.Repository select(UUID owner, String name) throws IOException {
		validateName(name);
		JsonObject repo = object(transport.request("GET", "https://api.github.com/repos/" + name, requiredToken(owner), "", false));
		if (!writable(repo)) throw new IOException("此仓库不可写、已归档或已禁用，请选择其他仓库");
		LuaGit.Repository selected = repository(repo);
		if (!selected.fullName().equalsIgnoreCase(name)) throw new IOException("仓库已改名，请刷新列表后重新选择");
		return selected;
	}
	LuaGit.Repository create(UUID owner, LuaGit.CreateRepository request) throws IOException {
		if (request == null || request.name() == null || !request.name().matches("[A-Za-z0-9_.-]{1,100}")
				|| request.name().contains("..") || request.name().equals(".")) throw new IOException("仓库名称仅支持 1–100 位字母、数字、点、下划线和连字符");
		String description = request.description() == null ? "" : request.description().strip();
		if (description.length() > 350 || description.chars().anyMatch(Character::isISOControl)) throw new IOException("仓库简介最多 350 个可见字符");
		JsonObject body = new JsonObject(); body.addProperty("name", request.name()); body.addProperty("description", description);
		body.addProperty("private", request.privateRepo()); body.addProperty("auto_init", false);
		return repository(object(transport.request("POST", "https://api.github.com/user/repos", requiredToken(owner), body.toString(), false)));
	}
	static void validateName(String name) throws IOException {
		GitWorkspaceService.validateRepository("https://github.com/" + name + ".git");
		if (name == null || !name.matches("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}") || name.contains("..")) throw new IOException("仓库名称格式无效");
	}
	private static boolean writable(JsonObject repo) {
		return !bool(repo, "archived") && !bool(repo, "disabled") && repo.has("permissions")
				&& repo.get("permissions").isJsonObject() && bool(repo.getAsJsonObject("permissions"), "push");
	}
	private static LuaGit.Repository repository(JsonObject repo) throws IOException {
		String name = value(repo, "full_name", 140); validateName(name);
		String url = value(repo, "clone_url", 200); GitWorkspaceService.validateRepository(url);
		if (!url.equals("https://github.com/" + name + ".git")) throw new IOException("GitHub 仓库地址与名称不匹配");
		String branch = optional(repo, "default_branch", 240); if (branch.isEmpty()) branch = "main";
		GitWorkspaceService.validateBranch(branch);
		String description = optional(repo, "description", 3500);
		return new LuaGit.Repository(name, url, branch, bool(repo, "private"), description.substring(0, Math.min(350, description.length())));
	}
	private static boolean bool(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean(); }
	private static JsonObject object(JsonElement json) throws IOException {
		if (json == null || !json.isJsonObject()) throw new IOException("GitHub 响应格式无效"); return json.getAsJsonObject();
	}
	private static String value(JsonObject json, String key, int maximum) throws IOException {
		String value = optional(json, key, maximum); if (value.isEmpty()) throw new IOException("GitHub 响应缺少必要字段"); return value;
	}
	private static String optional(JsonObject json, String key, int maximum) throws IOException {
		if (!json.has(key) || json.get(key).isJsonNull()) return "";
		if (!json.get(key).isJsonPrimitive()) throw new IOException("GitHub 响应字段格式无效");
		String value = json.get(key).getAsString();
		if (value.length() > maximum) throw new IOException("GitHub 响应字段超出安全限制");
		return value;
	}
	private static int integer(JsonObject json, String key, int fallback) { return json.has(key) ? json.get(key).getAsInt() : fallback; }
	private static String form(String... pairs) {
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < pairs.length; i += 2) { if (!body.isEmpty()) body.append('&'); body.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8)); }
		return body.toString();
	}
	private static void oauthError(JsonObject json) throws IOException {
		String error = optional(json, "error", 100); if (error.isEmpty()) return;
		throw new IOException(switch (error) {
			case "access_denied" -> "你已取消 GitHub 授权，请重新登录";
			case "expired_token" -> "GitHub 验证码已过期，请重新登录";
			case "device_flow_disabled" -> "请在 GitHub OAuth App 设置中启用 Device Flow";
			case "incorrect_client_credentials", "invalid_client" -> "Client ID 无效，请检查 GitHub OAuth App 设置";
			default -> "GitHub 登录失败，请检查 OAuth App 设置或稍后重试";
		});
	}
	private static final class WebTransport implements Transport {
		private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
		@Override public JsonElement request(String method, String url, String token, String body, boolean form) throws IOException {
			URI uri = URI.create(url);
			if (!uri.getScheme().equals("https") || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
					|| !(uri.getHost().equals("github.com") && uri.getPath().startsWith("/login/") || uri.getHost().equals("api.github.com")))
				throw new IOException("禁止向非 GitHub 地址发送认证信息");
			HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
					.header("Accept", form ? "application/json" : "application/vnd.github+json").header("User-Agent", "MC-Lua-IDE/1");
			if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
			if (!form) request.header("X-GitHub-Api-Version", "2022-11-28");
			if (method.equals("POST")) request.header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)); else request.GET();
			try {
				HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 1_048_576));
				byte[] bytes = response.body();
				// Never surface raw OAuth/REST bodies: they may contain credential material.
				if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException(switch (response.statusCode()) {
					case 401 -> "GitHub 登录失效，请退出登录后重新授权";
					case 403 -> "GitHub 拒绝访问：请检查授权范围、组织 SSO 或 API 频率限制";
					case 404 -> "GitHub 仓库不存在或账号没有访问权限";
					case 422 -> "GitHub 拒绝创建：名称已存在、名称无效或账号受限";
					case 429 -> "GitHub 请求过于频繁，请稍后重试";
					default -> "GitHub 请求失败（HTTP " + response.statusCode() + "），请稍后重试";
				});
				return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
			} catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("GitHub 请求已取消"); }
			catch (IllegalArgumentException | com.google.gson.JsonParseException error) { throw new IOException("GitHub 响应格式无效"); }
			catch (IOException error) {
				if (error instanceof java.net.http.HttpTimeoutException || error instanceof java.net.ConnectException)
					throw new IOException("无法连接 GitHub，请检查服务器网络后重试");
				throw error;
			}
		}
	}
}
