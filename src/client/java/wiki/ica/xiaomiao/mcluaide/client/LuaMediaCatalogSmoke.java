package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;

public final class LuaMediaCatalogSmoke {
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/resourcepacks/silence.ogg"));
		LuaBinaryPreview preview = new LuaBinaryPreview(null, "test/assets/test/sounds/demo.ogg", bytes);
		require(field(preview, "error").equals(""), "Valid OGG could not be previewed");
		String detail = (String) field(preview, "detail");
		require(detail.contains("Vorbis") && detail.contains("22050") && detail.contains("2 声道"), "OGG format details missing");
		LuaOggMetadata metadata = (LuaOggMetadata) field(preview, "audio");
		require(Math.abs(metadata.duration() - 1152.0 / 22050) < 0.000001, "Vorbis end-page sample count did not produce the correct duration: " + metadata.duration());
		require(!metadata.encoder().isBlank() && metadata.averageBitrate(bytes.length) > 0, "Audio encoder/bitrate details missing");
		require(LuaOggMetadata.time(metadata.duration()).equals("00:00.0")
				&& LuaOggMetadata.time(65.4).equals("01:05.4") && LuaOggMetadata.time(3661.2).equals("1:01:01.2")
				&& LuaOggMetadata.time(-1).equals("未知"), "Playback time formatting incorrect");
		@SuppressWarnings("unchecked") List<String> audioRows = (List<String>) field(preview, "audioDetails");
		require(audioRows.stream().anyMatch(row -> row.startsWith("总时长："))
				&& audioRows.stream().anyMatch(row -> row.startsWith("平均码率")), "Audio details not exposed in preview");
		require(preview.scroll(-1, 0, 210) && (int) field(preview, "scroll") > 0, "Audio details cannot scroll in a short viewport");
		require(Math.abs(OggPreviewChannel.bufferSeconds(88200, 16, 2, 22050) - 1) < 0.000001
				&& OggPreviewChannel.bufferSeconds(0, 16, 2, 22050) == 0
				&& OggPreviewChannel.bufferSeconds(10, 0, 0, 0) == 0, "Streaming buffer time accounting incorrect");
		LuaOggPreviewPlayer.stop();
		require(LuaOggPreviewPlayer.position() == 0 && !LuaOggPreviewPlayer.playing(), "Stop did not reset audio position");
		require(LuaBinaryPreview.audioPosition(-100, 12, 176, 10) == 0
				&& LuaBinaryPreview.audioPosition(100, 12, 176, 10) == 5
				&& LuaBinaryPreview.audioPosition(500, 12, 176, 10) == 10, "Seek drag position/clamping incorrect");
		preview.click(null, 0, 100, 117, 0, 0, 200, 250);
		require((boolean) field(preview, "audioDragging") && Math.abs((double) field(preview, "audioDragPosition") - metadata.duration() / 2) < 0.000001,
				"Progress bar press did not begin a drag at the clicked position");
		require(preview.drag(-100, 117) && (double) field(preview, "audioDragPosition") == 0, "Dragging outside the bar did not clamp");
		preview.drag(100, 117);
		require(preview.release(null, 1, 100) && (boolean) field(preview, "audioDragging"), "Right-button release ended a left-button drag");
		preview.release(null, 0, 500); // End-of-file seek needs no device and retains 100%.
		require(!(boolean) field(preview, "audioDragging") && LuaOggPreviewPlayer.position() == metadata.duration()
				&& LuaOggPreviewPlayer.state().equals("播放结束"), "Release/end-of-file seek did not finish at 100%");
		preview.click(null, 0, 100, 117, 0, 0, 200, 250);
		preview.activate(null);
		require(!preview.drag(50, 117), "Tab reactivation retained a stale progress drag");
		LuaOggPreviewPlayer.stop();
		int skipFrames = 576;
		try (var seek = LuaOggSeekStream.open(bytes, skipFrames / 22050.0, () -> false)) {
			require(seek instanceof LuaOggSeekStream, "Native random seek unexpectedly used fallback");
			require(readFrames(seek) == 1152 - skipFrames, "Native seek did not start at the requested sample");
			seek.close(); seek.close();
		}
		try (var seek = LuaOggSeekStream.skipGameStream(bytes, skipFrames / 22050.0, () -> false)) {
			require(readFrames(seek) == 1152 - skipFrames, "Game-decoder fallback lost the partial seek buffer");
		}
		boolean cancelled = false;
		try { LuaOggSeekStream.open(bytes, 0.01, () -> true); }
		catch (java.util.concurrent.CancellationException expected) { cancelled = true; }
		require(cancelled, "Cancelled seek still prepared a stream");
		LuaBinaryPreview truncated = new LuaBinaryPreview(null, "cut.ogg", Arrays.copyOf(bytes, bytes.length - 1));
		require(!field(truncated, "error").equals(""), "Truncated OGG page silently accepted");
		// Removing the final page still leaves decodable headers, but must not report a made-up duration.
		int lastPage = 0;
		for (int offset = 0; offset < bytes.length;) {
			lastPage = offset;
			int count = bytes[offset + 26] & 255;
			int next = offset + 27 + count;
			for (int s = 0; s < count; s++) next += bytes[offset + 27 + s] & 255;
			offset = next;
		}
		require(LuaOggMetadata.read(Arrays.copyOf(bytes, lastPage)).duration() == -1, "Missing end page should have unknown duration");
		LuaBinaryPreview invalid = new LuaBinaryPreview(null, "test/sounds/bad.ogg", new byte[]{1, 2, 3});
		require(!field(invalid, "error").equals(""), "Invalid audio silently accepted");
		preview.close(null); invalid.close(null); truncated.close(null);

		Field workspace = LuaResourceAssetCatalog.class.getDeclaredField("workspaceFiles"); workspace.setAccessible(true);
		workspace.set(null, List.of(new LuaResourcePackWorkspace.Entry("pack/assets/test/models/item/demo.json", false, 5),
				new LuaResourcePackWorkspace.Entry("pack/assets/test/items/demo.json", false, 4),
				new LuaResourcePackWorkspace.Entry("pack/assets/test/textures/item/demo.png", false, 5),
				new LuaResourcePackWorkspace.Entry("pack/assets/test/sounds/music/demo.ogg", false, 5),
				new LuaResourcePackWorkspace.Entry("pack/assets/test/textures/gui/sprites/tooltip/demo_background.png", false, 7),
				new LuaResourcePackWorkspace.Entry("pack/assets/test/textures/gui/sprites/tooltip/demo_frame.png", false, 7)));
		ResourceManager resources = (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
				new Class<?>[]{ResourceManager.class}, (proxy, method, arguments) -> {
			if (method.getName().equals("listResources")) {
				@SuppressWarnings("unchecked") Predicate<Identifier> filter = (Predicate<Identifier>) arguments[1];
				Map<Identifier, Object> loaded = new LinkedHashMap<>();
				for (String raw : List.of("minecraft:models/item/diamond.json", "test:models/item/demo.json")) {
					Identifier id = Identifier.parse(raw); if (filter.test(id)) loaded.put(id, null);
				}
				return loaded;
			}
			throw new UnsupportedOperationException(method.getName());
		});
		Method scan = LuaResourceAssetCatalog.class.getDeclaredMethod("scan", ResourceManager.class, String.class, String.class, String.class);
		scan.setAccessible(true);
		List<LuaInsertCatalog.Entry> models = scan(scan, resources, "models", ".json");
		require(models.size() == 2 && models.stream().anyMatch(e -> e.value().equals("test:item/demo")), "Loaded/workspace model union lost or duplicated IDs");
		require(models.stream().filter(e -> e.value().equals("test:item/demo")).noneMatch(e -> e.description().contains("尚未")), "Loaded asset marked pending");
		List<LuaInsertCatalog.Entry> items = scan(scan, resources, "items", ".json");
		require(items.size() == 1 && items.getFirst().value().equals("test:demo") && items.getFirst().description().contains("尚未"), "Pending item model definition not distinguished");
		require(scan(scan, resources, "textures", ".png").stream().anyMatch(e -> e.value().equals("test:item/demo")), "Texture IDs missing");
		require(scan(scan, resources, "sounds", ".ogg").getFirst().value().equals("test:music/demo"), "OGG file ID mapping wrong");
		Method styles = LuaResourceAssetCatalog.class.getDeclaredMethod("tooltipStyles", ResourceManager.class); styles.setAccessible(true);
		@SuppressWarnings("unchecked") var styleEntries = (List<LuaInsertCatalog.Entry>) styles.invoke(null, resources);
		require(styleEntries.size() == 1 && styleEntries.getFirst().value().equals("test:demo"), "Workspace tooltip sprite pair missing");
		Map<String, LuaInsertCatalog.Entry> soundIds = new LinkedHashMap<>();
		LuaResourceAssetCatalog.addSounds(soundIds, "test", JsonParser.parseString("{\"music.demo\":{\"sounds\":[\"test:music/demo\"]},\"Bad Name\":{}}").getAsJsonObject(), "pending");
		require(soundIds.size() == 1 && soundIds.containsKey("test:music.demo"), "Sound event ID confused with OGG path or accepted invalid ID");
		require(LuaInsertCatalog.Kind.group(true).size() == 10
				&& LuaInsertCatalog.Kind.group(true).stream().allMatch(kind -> kind.registryId().startsWith("mc_lua_ide:"))
				&& LuaInsertCatalog.Kind.group(false).contains(LuaInsertCatalog.Kind.SOUND)
                && !LuaInsertCatalog.Kind.group(false).contains(LuaInsertCatalog.Kind.ITEM_MODEL), "Resource registry ownership metadata changed");

		LuaInsertOverlay picker = new LuaInsertOverlay(null, null, ignored -> { });
		@SuppressWarnings("unchecked") Map<LuaInsertCatalog.Kind, List<LuaInsertCatalog.Entry>> oldCatalog =
				(Map<LuaInsertCatalog.Kind, List<LuaInsertCatalog.Entry>>) field(picker, "catalogs");
		oldCatalog.put(LuaInsertCatalog.Kind.SOUND, List.copyOf(soundIds.values()));
		int revision = ScriptManager.catalogRevision();
		ScriptManager.invalidateAllRegistryCatalogs(); picker.tick();
		require(ScriptManager.catalogRevision() != revision && workspace.get(null) == null && oldCatalog.isEmpty(), "Refresh retained stale picker/workspace cache");
		System.out.println("OGG preview and resource ID refresh smoke passed");
	}
	@SuppressWarnings("unchecked") private static List<LuaInsertCatalog.Entry> scan(Method method, ResourceManager resources, String directory, String suffix) throws Exception {
		return (List<LuaInsertCatalog.Entry>) method.invoke(null, resources, directory, suffix, "loaded");
	}
	private static Object field(Object object, String name) throws Exception {
		Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
	}
	private static int readFrames(net.minecraft.client.sounds.AudioStream stream) throws Exception {
		int total = 0;
		for (int i = 0; i < 100; i++) {
			var data = stream.read(128);
			if (data == null || !data.hasRemaining()) return total;
			total += data.remaining() / stream.getFormat().getFrameSize();
		}
		throw new AssertionError("Seek stream did not reach EOF");
	}
	private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
