package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import wiki.ica.xiaomiao.mcluaide.network.LuaGit;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Synthetic OAuth/REST only: never authorizes, creates or pushes a real GitHub repository. */
public final class GitHubAccountSmoke {
	private static int checks;
	private static final String ID = "0123456789abcdef1234", SECRET = "synthetic_private_access_token", DEVICE_CODE = "synthetic_secret_device_code";
	private static class Fake implements GitHubAccounts.Transport {
		final ArrayDeque<String> replies = new ArrayDeque<>(); int calls; String method, url, token, body;
		@Override public JsonElement request(String method, String url, String token, String body, boolean form) throws IOException {
			calls++; this.method = method; this.url = url; this.token = token; this.body = body;
			if (replies.isEmpty()) throw new AssertionError("Unexpected HTTP request: " + method + " " + url);
			return JsonParser.parseString(replies.remove());
		}
	}
	private static String device() { return "{\"device_code\":\"" + DEVICE_CODE + "\",\"user_code\":\"ABCD-1234\",\"verification_uri\":\"https://github.com/login/device\",\"interval\":5,\"expires_in\":900}"; }
	private static String grant() { return "{\"access_token\":\"" + SECRET + "\",\"token_type\":\"bearer\",\"scope\":\"repo\",\"expires_in\":3600}"; }
	private static String repo(String name, boolean writable, boolean archived) {
		return "{\"full_name\":\"owner/" + name + "\",\"clone_url\":\"https://github.com/owner/" + name + ".git\",\"default_branch\":\"main\",\"private\":true,\"description\":\"Lua 示例\",\"archived\":" + archived + ",\"permissions\":{\"push\":" + writable + "}}";
	}
	public static void main(String[] args) throws Exception {
		var root = Files.createTempDirectory("mc-lua-github-smoke-").toAbsolutePath().normalize();
		try {
			Fake fake = new Fake(); AtomicLong now = new AtomicLong(1000000); UUID admin = UUID.randomUUID(), other = UUID.randomUUID();
			GitHubAccounts accounts = new GitHubAccounts(root, fake, now::get, "");
			check(accounts.account(admin).phase() == LuaGit.LoginPhase.SIGNED_OUT, "Fresh install directly offers browser login");
			check(accounts.clientId().equals("Ov23li9ALF0BE5QLuRIs"), "User-provided public OAuth App is the bundled default");
			check(!Files.exists(root.resolve("github-login.properties")), "Default login needs no generated server configuration");
			check(fake.calls == 0, "Opening the account page does not initiate authorization");
			fails(() -> accounts.saveClientId("ghp_do_not_use_a_personal_token"), "PAT cannot be mistaken for client ID");
			fails(() -> accounts.saveClientId("bad\nclient_id"), "No control characters in client ID");
			accounts.saveClientId(ID);
			check(accounts.account(admin).phase() == LuaGit.LoginPhase.SIGNED_OUT, "Configuration enables browser choice");
			check(Files.readString(root.resolve("github-login.properties")).contains(ID), "Only public client ID persisted");
			fake.replies.add(device()); LuaGit.Account pending = accounts.begin(admin);
			check(fake.method.equals("POST") && fake.url.endsWith("/login/device/code"), "Device flow endpoint");
			check(fake.body.contains("scope=repo") && fake.body.contains("client_id=" + ID) && fake.token.isEmpty(), "Initial request has no access token or client secret");
			check(pending.phase() == LuaGit.LoginPhase.PENDING && pending.userCode().equals("ABCD-1234"), "UI receives only user code");
			check(!LuaGit.JSON.toJson(pending).contains(DEVICE_CODE), "Secret device code is absent from protocol DTO");
			accounts.poll(admin); check(fake.calls == 1, "Server enforces polling interval even for a spammed request");
			accounts.begin(admin); check(fake.calls == 1, "Repeated login reuses pending device authorization");
			now.addAndGet(5000); fake.replies.add("{\"error\":\"authorization_pending\"}"); accounts.poll(admin);
			check(fake.body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"), "Correct grant exchange");
			now.addAndGet(5000); fake.replies.add("{\"error\":\"slow_down\"}");
			check(accounts.poll(admin).pollSeconds() == 10, "slow_down increases interval by five seconds");
			now.addAndGet(5000); accounts.poll(admin); check(fake.calls == 3, "Backoff cannot be bypassed");
			now.addAndGet(5000); fake.replies.add(grant()); fake.replies.add("{\"login\":\"owner\"}");
			LuaGit.Account connected = accounts.poll(admin);
			check(connected.phase() == LuaGit.LoginPhase.CONNECTED && connected.login().equals("owner"), "Login validates account identity");
			check(fake.url.equals("https://api.github.com/user") && fake.token.equals(SECRET), "Token used only by backend transport");
			check(!LuaGit.JSON.toJson(connected).contains(SECRET) && connected.userCode().isEmpty(), "Token is never sent to client");
			check(accounts.token(admin).equals(SECRET) && accounts.token(other).isEmpty(), "Administrators cannot borrow each other's login");
			fails(() -> accounts.repositories(other, 1), "Other administrator must sign in");
			fake.replies.add("[" + repo("editable", true, false) + "," + repo("readonly", false, false) + "," + repo("archived", true, true) + "]");
			LuaGit.Repositories repos = accounts.repositories(admin, 1);
			check(repos.repositories().size() == 1 && repos.repositories().getFirst().fullName().equals("owner/editable"), "Only writable unarchived repositories shown");
			check(fake.url.contains("affiliation=owner,collaborator,organization_member") && fake.url.contains("per_page=30&page=1"), "Pagination includes organization and collaborator repos");
			check(!repos.more(), "Short page ends pagination");
			fake.replies.add("[" + java.util.stream.IntStream.range(0, 30).mapToObj(i -> repo("repo" + i, false, false)).collect(java.util.stream.Collectors.joining(",")) + "]");
			repos = accounts.repositories(admin, 2); check(repos.more() && repos.repositories().isEmpty(), "Filtered full page still permits reaching later pages");
			fails(() -> accounts.repositories(admin, 0), "Reject invalid page");
			fake.replies.add(repo("editable", true, false)); check(accounts.select(admin, "owner/editable").branch().equals("main"), "Selection checks permissions and supplies default branch");
			fake.replies.add(repo("editable", true, false).replace("\"default_branch\":\"main\"", "\"default_branch\":\"release/v1.2\""));
			check(accounts.select(admin, "owner/editable").branch().equals("release/v1.2"), "Default branch may include a Git hierarchy");
			fake.replies.add(repo("readonly", false, false)); fails(() -> accounts.select(admin, "owner/readonly"), "Direct read-only selection rejected");
			fails(() -> accounts.select(admin, "../secret"), "No path injection in GitHub API URL");
			fake.replies.add(repo("new_repo", true, false));
			LuaGit.Repository created = accounts.create(admin, new LuaGit.CreateRepository("new_repo", "脚本与数据包", true));
			JsonElement createBody = JsonParser.parseString(fake.body);
			check(fake.method.equals("POST") && fake.url.equals("https://api.github.com/user/repos"), "Creates only in authenticated user's account");
			check(createBody.getAsJsonObject().get("private").getAsBoolean() && !createBody.getAsJsonObject().get("auto_init").getAsBoolean(), "Private empty repository, no overwrite or README conflict");
			check(created.fullName().equals("owner/new_repo"), "Created repository available for selection");
			fails(() -> accounts.create(admin, new LuaGit.CreateRepository("../outside", "", true)), "Unsafe repository name rejected before HTTP");
			check(!Files.readString(root.resolve("github-login.properties")).contains(SECRET), "No stored access token after login");
			long generation = accounts.generation(admin); accounts.logout(admin);
			check(accounts.token(admin).isEmpty(), "Logout removes backend credentials");
			fails(() -> accounts.begin(admin, generation), "Queued login cannot resurrect credentials after disconnection");
			fake.replies.add(device()); accounts.begin(admin); now.addAndGet(901000);
			check(accounts.poll(admin).phase() == LuaGit.LoginPhase.SIGNED_OUT, "Expired device code clears pending session");
			fake.replies.add(device()); accounts.begin(admin); now.addAndGet(5000); fake.replies.add("{\"error\":\"access_denied\"}");
			fails(() -> accounts.poll(admin), "Denied authorization yields Chinese explanation");
			check(accounts.account(admin).phase() == LuaGit.LoginPhase.SIGNED_OUT, "Denied authorization clears session");
			fake.replies.add(device().replace("https://github.com/login/device", "https://evil.invalid/login"));
			fails(() -> accounts.begin(admin), "Verification URL pinned to real GitHub");
			fake.replies.add(device()); accounts.begin(admin); now.addAndGet(5000); fake.replies.add(grant().replace("\"repo\"", "\"gist\""));
			fails(() -> accounts.poll(admin), "Insufficient OAuth scopes rejected");
			fake.replies.add(device()); accounts.begin(admin); now.addAndGet(5000); fake.replies.add(grant()); fake.replies.add("{\"login\":\"owner\"}"); accounts.poll(admin);
			now.addAndGet(3600001); check(accounts.token(admin).isEmpty(), "Expiring access token is not silently reused");
			fake.replies.add(device()); accounts.begin(admin); accounts.saveClientId(ID);
			check(accounts.account(admin).phase() == LuaGit.LoginPhase.SIGNED_OUT, "Changing public App config invalidates sessions");
			GitHubAccounts environment = new GitHubAccounts(root, fake, now::get, "environmentClient1234");
			check(environment.clientId().equals("environmentClient1234"), "Server environment overrides file setting");
			fails(() -> environment.saveClientId(ID), "Client cannot override server-owned environment config");
			accounts.saveClientId("");
			check(accounts.clientId().equals(GitHubAccounts.DEFAULT_CLIENT_ID), "Empty saved setting restores bundled default");
			fake.replies.add(device()); accounts.begin(admin);
			check(fake.body.contains("client_id=" + GitHubAccounts.DEFAULT_CLIENT_ID), "Browser login uses bundled Client ID when no override exists");
			accounts.logout(admin);
			check(new GitHubAccounts(root, fake, now::get, "   ").clientId().equals(GitHubAccounts.DEFAULT_CLIENT_ID), "Blank environment does not mask bundled default");
			check(environment.clientId().equals("environmentClient1234"), "Environment still has highest priority after resetting file");
			check(fake.replies.isEmpty(), "No unexpected fake API calls left");
			System.out.println("GitHub account smoke passed: " + checks + " checks (synthetic HTTP; no real account writes)");
		} finally {
			// Exact fixture created by this test; never follow links or delete any workspace.
			if (root.getFileName().toString().startsWith("mc-lua-github-smoke-")) try (var paths = Files.walk(root)) {
				for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}
	private interface Throwing { void run() throws Exception; }
	private static void fails(Throwing work, String explanation) throws Exception { try { work.run(); throw new AssertionError(explanation); } catch (IOException expected) { checks++; check(expected.getMessage() != null && !expected.getMessage().contains(SECRET), "Errors do not expose credentials"); } }
	private static void check(boolean condition, String explanation) { if (!condition) throw new AssertionError(explanation); checks++; }
}
