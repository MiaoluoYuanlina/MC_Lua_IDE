package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Isolated crash simulation: omit clean-close cleanup, reopen the on-disk recovery store. */
public final class LuaFileRecoverySmoke {
	private static int checks;
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		Path root = Files.createTempDirectory("mc_lua_recovery_smoke_");
		try {
			LuaIdeSession session = new LuaIdeSession(root, "world:测试:player:admin");
			LuaFileRecovery store = new LuaFileRecovery(session);
			var doc = new LuaIdeSession.Document("目录/测试", false, false, 10, 13.5);
			String id = UUID.randomUUID().toString();
			store.save(draft(id, doc, "local 测试 = '未保存'\n", "print('已保存')"));
			store.flush().join();
			LuaFileRecovery reopened = new LuaFileRecovery(session);
			require(reopened.entries().size() == 1, "Abnormal exit lost draft");
			var recovered = reopened.read(id).join();
			require(recovered.source().equals("local 测试 = '未保存'\n"), "Unicode content changed");
			require(recovered.savedSource().equals("print('已保存')"), "Dirty baseline lost");
			require(recovered.entry().document().equals(doc), "Cursor/path/scroll lost");
			require(new LuaFileRecovery(new LuaIdeSession(root, "world:other:player:admin")).entries().isEmpty(), "World leaked");
			require(new LuaFileRecovery(new LuaIdeSession(root, "world:测试:player:other")).entries().isEmpty(), "Player leaked");
			String second = UUID.randomUUID().toString();
			store.save(draft(second, doc, "new version", "baseline")); store.flush().join();
			require(new LuaFileRecovery(session).entries().size() == 2, "Ignored previous draft overwritten by new edit");
			for (int i = 0; i < 1000; i++) store.save(draft(second, doc, "latest " + i, "baseline"));
			store.flush().join();
			require(new LuaFileRecovery(session).read(second).join().source().equals("latest 999"), "Coalesced write order wrong");
			store.save(draft(second, doc, "queued", "baseline")); store.discard(second); store.flush().join();
			require(new LuaFileRecovery(session).entries().size() == 1, "Discard overtaken by pending write");
			require(new LuaFileRecovery(session).read(id).join().source().contains("未保存"), "Cleanup removed unaccepted old draft");
			String bigId = UUID.randomUUID().toString(); String large = "中文".repeat(300_000);
			store.save(draft(bigId, doc, large, "")); store.flush().join();
			require(new LuaFileRecovery(session).read(bigId).join().source().equals(large), "Large file truncated");
			String pngId = UUID.randomUUID().toString();
			BufferedImage original = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
			var imageDoc = new LuaPngLayerDocument(root.resolve("unchanged.png"), original, root.resolve("project.zip"));
			imageDoc.addLayer(); imageDoc.beginStroke(); imageDoc.stroke(2, 2, LuaPngLayerDocument.Tool.PENCIL, 0xFF123456, 1); imageDoc.endStroke();
			imageDoc.toggleVisible(0); long revision = imageDoc.recoveryRevision();
			var image = imageDoc.recoveryImage();
			var pngMeta = new LuaIdeSession.Document("test/assets/test/textures/test.png", true, true, 0, 0);
			store.save(new LuaFileRecovery.Draft(new LuaFileRecovery.Entry(pngId, pngMeta, 10, true), "", "", image));
			imageDoc.beginStroke(); imageDoc.stroke(2, 2, LuaPngLayerDocument.Tool.PENCIL, 0xFFABCDEF, 1); imageDoc.endStroke();
			require(imageDoc.recoveryRevision() > revision, "Image edit revision not advanced");
			store.flush().join();
			var restoredImage = new LuaFileRecovery(session).read(pngId).join().image();
			require(restoredImage.layers().size() == 2 && restoredImage.selected() == 1, "Layers/selection lost");
			require(!restoredImage.layers().getFirst().visible(), "Layer visibility lost");
			require(restoredImage.layers().get(1).image().getRGB(2, 2) == 0xFF123456, "Mutable image snapshot changed during encoding");
			imageDoc.restoreRecovery(restoredImage);
			require(imageDoc.dirty() && imageDoc.composite().getRGB(2, 2) == 0xFF123456, "Restored image not editable/dirty");
			require(!Files.exists(root.resolve("unchanged.png")) && !Files.exists(root.resolve("project.zip")), "Recovery wrote original PNG/project");
			Files.writeString(store.directory.resolve("damaged.zip"), "broken");
			require(new LuaFileRecovery(session).entries().size() == 3, "Damaged draft prevented other recovery");
			try { store.discard("../../outside"); throw new AssertionError("Unsafe id accepted"); } catch (IllegalArgumentException expected) { checks++; }
			try { store.save(draft(UUID.randomUUID().toString(), new LuaIdeSession.Document("../outside", false, false, 0, 0), "x", "")); throw new AssertionError("Unsafe path accepted"); }
			catch (IllegalArgumentException expected) { checks++; }
			for (var entry : store.entries()) store.discard(entry.id()); store.flush().join();
			require(new LuaFileRecovery(session).entries().isEmpty(), "Clean close cleanup left drafts");
			try (var paths = Files.list(store.directory)) { require(paths.noneMatch(p -> p.toString().endsWith(".tmp")), "Atomic temp files leaked"); }
			var failureSession = new LuaIdeSession(root.resolve("failure-test"), "local");
			var failureStore = new LuaFileRecovery(failureSession);
			Files.createDirectories(failureStore.directory.getParent()); Files.createFile(failureStore.directory);
			String failedId = UUID.randomUUID().toString();
			failureStore.save(draft(failedId, doc, "retry me", "")); failureStore.flush().join();
			require(!failureStore.failure().isEmpty(), "Disk failure not surfaced");
			Files.delete(failureStore.directory); failureStore.retryFailures(); failureStore.flush().join();
			require(failureStore.failure().isEmpty() && new LuaFileRecovery(failureSession).read(failedId).join().source().equals("retry me"), "Failed backup not retried");
			failureStore.discard(failedId); failureStore.flush().join();
			System.out.println("File recovery smoke passed: " + checks + " checks");
		} finally {
			try (var paths = Files.walk(root)) { for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
		}
	}
	private static LuaFileRecovery.Draft draft(String id, LuaIdeSession.Document doc, String source, String saved) {
		return new LuaFileRecovery.Draft(new LuaFileRecovery.Entry(id, doc, System.currentTimeMillis(), false), source, saved, null);
	}
	private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
