package wiki.ica.xiaomiao.mcluaide.network;

import com.google.gson.Gson;
import java.net.URI;

/** Shared, bounded settings for vanilla-client resource-pack distribution. */
public final class LuaServerPack {
	public static final long MAX_BYTES = 128L * 1024 * 1024;
	public static final int CHUNK_BYTES = 48 * 1024;
	private static final Gson JSON = new Gson();
	private LuaServerPack() { }
	public record Settings(boolean enabled, boolean hosted, int port, String url, boolean required) {
		public Settings {
			if (port < 1024 || port > 65535) throw new IllegalArgumentException("下载服务端口必须在 1024–65535 之间");
			url = url == null ? "" : url.strip();
			if (url.length() > 2048) throw new IllegalArgumentException("下载地址过长");
			if (!url.isEmpty()) {
				URI uri;
				try { uri = URI.create(url); } catch (RuntimeException error) { throw new IllegalArgumentException("下载地址格式无效"); }
				if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
						|| uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
					throw new IllegalArgumentException("请填写不含密码和 # 的 HTTP/HTTPS 下载地址");
				if (hosted && (uri.getQuery() != null || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))))
					throw new IllegalArgumentException("模组下载服务请填写基础地址，例如 http://服务器地址:8080，不带路径");
			}
			if (enabled && url.isEmpty()) throw new IllegalArgumentException("请先填写玩家能够访问的下载地址");
		}
		public static Settings defaults() { return new Settings(false, true, 8080, "", false); }
		public String encode() { return JSON.toJson(this); }
		public static Settings decode(String text) {
			Settings value = JSON.fromJson(text, Settings.class);
			if (value == null) throw new IllegalArgumentException("资源包设置为空");
			return value;
		}
		public String downloadUrl(String sha1) {
			if (!sha1.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("资源包 SHA-1 无效");
			return hosted ? url.replaceAll("/+$", "") + "/mc-lua-pack/" + sha1 + ".zip" : url;
		}
	}
	public record Status(Settings settings, String fileName, String sha1, long size, String downloadUrl, String problem) {
		public String encode() { return JSON.toJson(this); }
		public static Status decode(String text) { return JSON.fromJson(text, Status.class); }
	}
	public record Upload(String fileName, long size, String sha256) {
		public Upload {
			if (fileName == null || fileName.length() > 160 || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
					|| fileName.contains("/") || fileName.contains("\\") || fileName.chars().anyMatch(Character::isISOControl))
				throw new IllegalArgumentException("请选择 ZIP 资源包文件");
			if (size <= 0 || size > MAX_BYTES) throw new IllegalArgumentException("资源包需要 1 字节–128 MiB");
			if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("上传文件 SHA-256 无效");
		}
		public String encode() { return JSON.toJson(this); }
		public static Upload decode(String text) { return JSON.fromJson(text, Upload.class); }
	}
}
