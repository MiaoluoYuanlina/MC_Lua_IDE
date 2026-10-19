package wiki.ica.xiaomiao.mcluaide.server;

import wiki.ica.xiaomiao.mcluaide.network.LuaGit;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** All GitHub HTTP runs off the game thread; caller performs the OP permission check. */
final class GitHubWorkspaceService {
	private static GitHubAccounts accounts;
	private static synchronized GitHubAccounts accounts() {
		if (accounts == null) accounts = new GitHubAccounts(GitWorkspaceService.root()); return accounts;
	}
	static void disconnected(UUID owner) { accounts().logout(owner); }
	static void stopped() { accounts().clear(); }
	static String token(UUID owner) throws IOException { return accounts().token(owner); }
	static CompletableFuture<String> request(UUID owner, LuaProtocol.Action action, String body) {
		GitHubAccounts manager = accounts(); long generation = manager.generation(owner);
		return CompletableFuture.supplyAsync(() -> {
			try {
				manager.checkGeneration(owner, generation);
				Object response = switch (action) {
					case GITHUB_ACCOUNT -> accounts().account(owner);
					case GITHUB_CLIENT_ID -> { accounts().saveClientId(body.strip()); yield accounts().account(owner); }
					case GITHUB_LOGIN -> manager.begin(owner, generation);
					case GITHUB_POLL -> accounts().poll(owner);
					case GITHUB_LOGOUT -> { accounts().logout(owner); yield accounts().account(owner); }
					case GITHUB_REPOSITORIES -> accounts().repositories(owner, Integer.parseInt(body));
					case GITHUB_SELECT_REPOSITORY -> {
						LuaGit.Repository repo = accounts().select(owner, body);
						GitWorkspaceService.save(repo.url(), repo.branch() + "\nGITHUB"); yield repo;
					}
					case GITHUB_CREATE_REPOSITORY -> accounts().create(owner, LuaGit.JSON.fromJson(body, LuaGit.CreateRepository.class));
					default -> throw new IOException("未知 GitHub 操作");
				};
				return LuaGit.JSON.toJson(response);
			} catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
			catch (RuntimeException error) { throw new java.util.concurrent.CompletionException(new IOException("GitHub 请求格式无效")); }
		}, command -> Thread.ofVirtual().name("mc-lua-github").start(command));
	}
}
