package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.network.LuaMcpFileChanged;
import wiki.ica.xiaomiao.mcluaide.network.LuaTextChanges;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Random;

public final class LuaMcpFileSyncSmoke {
	private static int checks;
	public static void main(String[] args) {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		for (String a : List.of("", "abc", "a\nb\nc\n", "😀😃", "中😀文\n", "a\n\na\n"))
			for (String b : List.of("", "def", "a\nbb\nc\n", "😀😄", "中文\n", "a\n\na\n")) verify(a, b);
		String before = "local a = 1\nprint(a)\nlocal b = 2\nprint(b)\nlocal c = 3\nprint(c)\n";
		String after = before.replace("a = 1", "a = 11").replace("c = 3", "c = 33");
		var edits = LuaTextChanges.between(before, after);
		require(edits.size() == 2, "Separated edits must not highlight unchanged middle lines");
		require(LuaTextChanges.mapCursor(edits, before.indexOf("print(b)"), after.length()) == after.indexOf("print(b)"), "Caret must follow unchanged code between edits");
		Random random = new Random(81345);
		String[] pieces = {"a", "b", " ", "\n", "\r\n", "😀", "中", "123", "print(x)\n", "--行\n"};
		for (int i = 0; i < 1500; i++) {
			StringBuilder a = new StringBuilder(), b = new StringBuilder();
			for (int j = 0; j < 60; j++) { String p = pieces[random.nextInt(pieces.length)]; a.append(p); b.append(random.nextInt(7) == 0 ? pieces[random.nextInt(pieces.length)] : p); }
			verify(a.toString(), b.toString());
		}
		verify("x\n".repeat(25_000), "X\n" + "x\n".repeat(24_998) + "Y\n");
		String unicode = "中".repeat(5_000_000);
		var big = change("large", "", unicode);
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		try {
			LuaMcpFileChanged.CODEC.encode(buffer, big);
			require(buffer.readableBytes() < 16 * 1024 * 1024, "MCP snapshot exceeds large-packet bound");
			require(LuaMcpFileChanged.CODEC.decode(buffer).equals(big) && !buffer.isReadable(), "MCP packet round trip");
		} finally { buffer.release(); }
		reject(() -> new LuaMcpFileChanged("../bad", LuaTextChanges.hash(""), "", List.of()));
		reject(() -> new LuaMcpFileChanged("test", "bad", "", List.of()));
		reject(() -> new LuaMcpFileChanged("test", LuaTextChanges.hash(""), "a", List.of(new LuaTextChanges.Edit(0, 0, 0, 2))));
		LuaMcpFileSync.disconnected();
		var update = change("test", before, after); LuaMcpFileSync.receive(update);
		long first = LuaMcpFileSync.latest("test").revision();
		LuaMcpFileSync.dismissAll(); require(LuaMcpFileSync.latest("test").dismissed(), "Esc mark dismissal not retained");
		LuaMcpFileSync.receive(change("test", after, after + "print('next')"));
		require(!LuaMcpFileSync.latest("test").dismissed() && LuaMcpFileSync.latest("test").revision() > first, "New edit must restore highlights");
		LuaMcpFileSync.observedRead("test", "other version", first);
		require(LuaMcpFileSync.latest("test") != null, "Old read discarded a newer MCP update");
		LuaMcpFileSync.observedRead("test", "other version", LuaMcpFileSync.revision());
		require(LuaMcpFileSync.latest("test") == null, "New authoritative read must reject stale retained snapshot");
		for (int i = 0; i < 40; i++) LuaMcpFileSync.receive(change("file" + i, "", "x"));
		require(LuaMcpFileSync.snapshots().size() == 32, "Bounded file retention");
		LuaMcpFileSync.receive(big); LuaMcpFileSync.receive(change("large2", "", unicode)); LuaMcpFileSync.receive(change("large3", "", unicode));
		require(LuaMcpFileSync.snapshots().stream().mapToLong(v -> v.change().source().length()).sum() <= 10_000_000, "Bounded retained source memory");
		LuaMcpFileSync.saved("large3"); require(LuaMcpFileSync.latest("large3") == null, "Manual save must invalidate old snapshot");
		LuaMcpFileSync.disconnected(); require(LuaMcpFileSync.snapshots().isEmpty(), "Cross-server contents retained on disconnect");
		System.out.println("MCP file sync smoke passed: " + checks + " checks; multi-edit/Unicode diff, fuzz reconstruction, caret, bounded snapshots, protocol, dismissal, stale reads and disconnect");
	}
	static LuaMcpFileChanged change(String name, String before, String after) { return new LuaMcpFileChanged(name, LuaTextChanges.hash(before), after, LuaTextChanges.between(before, after)); }
	private static void verify(String before, String after) {
		var change = change("test", before, after); StringBuilder reconstructed = new StringBuilder(); int cursor = 0;
		for (var edit : change.edits()) {
			reconstructed.append(before, cursor, edit.oldStart()).append(after, edit.start(), edit.end()); cursor = edit.oldEnd();
			require(edit.start() == 0 || edit.start() == after.length() || !Character.isLowSurrogate(after.charAt(edit.start())), "Split surrogate at start");
			require(edit.end() == 0 || edit.end() == after.length() || !Character.isLowSurrogate(after.charAt(edit.end())), "Split surrogate at end");
		}
		reconstructed.append(before, cursor, before.length()); require(reconstructed.toString().equals(after), "Diff reconstruction mismatch");
	}
	private static void reject(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; } throw new AssertionError("Invalid packet accepted"); }
	private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
