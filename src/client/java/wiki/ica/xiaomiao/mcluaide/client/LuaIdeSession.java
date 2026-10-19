package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Client-only metadata. Never copies server script contents into the client configuration. */
final class LuaIdeSession {
	static final int MAX_DOCUMENTS = 32;
	final Path file;
	LuaIdeSession(Path configRoot, String scope) {
		try {
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(scope.getBytes(StandardCharsets.UTF_8)));
			file = configRoot.resolve("sessions").resolve(hash + ".properties");
		} catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}

	record Document(String name, boolean json, boolean resource, int cursor, double scroll, LuaFunctionFolding.Snapshot folds) {
		Document(String name, boolean json, boolean resource, int cursor, double scroll) {
			this(name, json, resource, cursor, scroll, LuaFunctionFolding.Snapshot.EMPTY);
		}
		Document { folds = folds == null || json ? LuaFunctionFolding.Snapshot.EMPTY : folds; }
		String key() { return (resource ? "resourcepack:" : json ? "datapack:" : "lua:") + name; }
		boolean valid() {
			return name != null && !name.isBlank() && name.length() <= 512 && !name.startsWith("/")
					&& !name.contains("\\") && !name.contains(":") && !name.contains("\0")
					&& Arrays.stream(name.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))
					&& (!resource || json);
		}
	}
	record State(List<Document> documents, String active) { }

	State load() {
		Properties properties = readProperties(file);
		List<Document> documents = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		int count = Math.clamp(integer(properties, "count", 0), 0, MAX_DOCUMENTS);
		for (int index = 0; index < count; index++) {
			String p = "document." + index + ".";
			double scroll;
			try { scroll = Double.parseDouble(properties.getProperty(p + "scroll", "0")); }
			catch (NumberFormatException ignored) { scroll = 0; }
			Document document = new Document(properties.getProperty(p + "name", ""),
					Boolean.parseBoolean(properties.getProperty(p + "json", "false")),
					Boolean.parseBoolean(properties.getProperty(p + "resource", "false")),
					Math.max(0, integer(properties, p + "cursor", 0)),
					Double.isFinite(scroll) ? Math.max(0, scroll) : 0, readFolds(properties, p));
			if (document.valid() && keys.add(document.key())) documents.add(document);
		}
		String active = properties.getProperty("active", "");
		if (!keys.contains(active)) active = documents.isEmpty() ? "" : documents.getFirst().key();
		return new State(List.copyOf(documents), active);
	}

	boolean save(State state) {
		Properties properties = new Properties();
		int index = 0;
		for (Document document : state.documents()) {
			if (!document.valid() || index >= MAX_DOCUMENTS) continue;
			String p = "document." + index++ + ".";
			properties.setProperty(p + "name", document.name());
			properties.setProperty(p + "json", Boolean.toString(document.json()));
			properties.setProperty(p + "resource", Boolean.toString(document.resource()));
			properties.setProperty(p + "cursor", Integer.toString(document.cursor()));
			properties.setProperty(p + "scroll", Double.toString(document.scroll()));
			if (!document.folds().starts().isEmpty()) {
				properties.setProperty(p + "folds.hash", document.folds().sourceHash());
				properties.setProperty(p + "folds.starts", document.folds().starts().stream()
						.map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
			}
		}
		properties.setProperty("count", Integer.toString(index));
		properties.setProperty("active", state.active());
		return writeProperties(file, properties);
	}

	private static LuaFunctionFolding.Snapshot readFolds(Properties properties, String prefix) {
		String encoded = properties.getProperty(prefix + "folds.starts", "");
		if (encoded.length() > 1_000_000) return LuaFunctionFolding.Snapshot.EMPTY;
		List<Integer> starts = new ArrayList<>();
		for (String raw : encoded.split(",", 100_001)) {
			if (starts.size() >= 100_000) break;
			try { starts.add(Integer.parseInt(raw)); } catch (NumberFormatException ignored) { }
		}
		return new LuaFunctionFolding.Snapshot(properties.getProperty(prefix + "folds.hash", ""), starts);
	}

	static Properties readProperties(Path file) {
		Properties properties = new Properties();
		if (!Files.isRegularFile(file)) return properties;
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
		catch (Exception error) { McLuaIdeMod.LOGGER.warn("Could not read IDE session/layout {}", file, error); }
		return properties;
	}

	static boolean writeProperties(Path file, Properties properties) {
		Path temporary = null;
		try {
			Files.createDirectories(file.getParent());
			temporary = Files.createTempFile(file.getParent(), "ide-state-", ".tmp");
			try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				properties.store(writer, "MC Lua IDE client session/layout");
			}
			try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
			catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
			return true;
		} catch (Exception error) {
			McLuaIdeMod.LOGGER.warn("Could not save IDE session/layout {}", file, error);
			return false;
		} finally {
			if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
		}
	}
	private static int integer(Properties properties, String key, int fallback) {
		try { return Integer.parseInt(properties.getProperty(key, "")); }
		catch (NumberFormatException ignored) { return fallback; }
	}
}
