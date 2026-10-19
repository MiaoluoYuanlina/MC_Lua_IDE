package wiki.ica.xiaomiao.mcluaide.network;

import com.google.gson.Gson;
import java.util.List;

/** Permission-checked Git data; never contains credentials or arbitrary filesystem paths. */
public final class LuaGit {
	public static final Gson JSON = new Gson();
	public enum Mode { LOCAL, GITHUB }
	/** These DTOs deliberately contain neither device codes nor access tokens. */
	public enum LoginPhase { CONFIGURE, SIGNED_OUT, PENDING, CONNECTED }
	public record Account(LoginPhase phase, String clientId, String login, String userCode,
			String verificationUri, long expiresAt, int pollSeconds, String notice) { }
	public record Repository(String fullName, String url, String branch, boolean privateRepo, String description) { }
	public record Repositories(List<Repository> repositories, int page, boolean more) { }
	public record CreateRepository(String name, String description, boolean privateRepo) { }
	public record CommitRequest(String summary, String description) { }
	public record DetailRequest(String sha, String file) { }
	public record Commit(String sha, long time, String summary, boolean published) { }
	public record History(List<Commit> commits, int offset, boolean more, String location, String notice) { }
	public record Change(String path, String status) { }
	public record Detail(String sha, String message, List<Change> files, int totalFiles, String file,
			String diff, String source, boolean truncated) { }
	private LuaGit() { }
}
