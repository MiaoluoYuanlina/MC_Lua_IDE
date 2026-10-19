package wiki.ica.xiaomiao.mcluaide.editor;

import net.minecraft.resources.Identifier;

/** Shared, path-safe mapping from physical pack files to logical resource identifiers. */
public final class PackResourceId {
	private PackResourceId() { }
	public static String logical(Identifier resource, String directory, String suffix, boolean tag) {
		String prefix = directory + "/", path = resource.getPath();
		if (!path.startsWith(prefix) || !path.endsWith(suffix)) return null;
		String logical = path.substring(prefix.length(), path.length() - suffix.length());
		if (logical.isBlank() || java.util.Arrays.stream(logical.split("/", -1))
				.anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) return null;
		Identifier id = Identifier.tryParse(resource.getNamespace() + ":" + logical);
		return id == null ? null : (tag ? "#" : "") + id;
	}
	public static Identifier workspaceFile(String path, String root) {
		int marker = path.indexOf("/" + root + "/");
		if (marker <= 0) return null;
		String relative = path.substring(marker + root.length() + 2);
		int slash = relative.indexOf('/');
		if (slash <= 0) return null;
		return Identifier.tryParse(relative.substring(0, slash) + ":" + relative.substring(slash + 1));
	}
}
