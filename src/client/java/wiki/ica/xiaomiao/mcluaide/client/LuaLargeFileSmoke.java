package wiki.ica.xiaomiao.mcluaide.client;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.util.ArrayDeque;
import java.util.List;

/** Bounded worst-case Unicode codecs, policy compatibility and snapshot memory checks. */
public final class LuaLargeFileSmoke {
	private static int checks;
	private static RegistryAccess registries;

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		String source = "中".repeat(LuaProtocol.MAX_SCRIPT_CHARS);
		for (var action : List.of(LuaProtocol.Action.SAVE, LuaProtocol.Action.RUN, LuaProtocol.Action.RUN_NORMAL,
				LuaProtocol.Action.RUN_FUNCTION, LuaProtocol.Action.SAVE_DATAPACK_FILE, LuaProtocol.Action.CREATE_DATAPACK_FILE)) {
			var buffer = buffer();
			try {
				var expected = new LuaProtocol.Request(123, action, "large/tools/script", source);
				LuaProtocol.Request.CODEC.encode(buffer, expected);
				require(buffer.readableBytes() > 14_000_000 && buffer.readableBytes() < 16 * 1024 * 1024, "Worst-case UTF-8 packet bound");
				require(LuaProtocol.Request.CODEC.decode(buffer).equals(expected) && !buffer.isReadable(), "Request round-trip " + action);
			} finally { buffer.release(); }
		}
		for (var action : List.of(LuaProtocol.Action.READ, LuaProtocol.Action.READ_DATAPACK_FILE)) {
			var buffer = buffer();
			try {
				var expected = new LuaProtocol.Response(123, action, true, source);
				LuaProtocol.Response.CODEC.encode(buffer, expected);
				require(LuaProtocol.Response.CODEC.decode(buffer).equals(expected), "Full response not truncated " + action);
			} finally { buffer.release(); }
		}
		String emoji = "-- 中文😀\n".repeat(50_000);
		var buffer = buffer();
		try {
			var expected = new LuaProtocol.Request(1, LuaProtocol.Action.SAVE, "unicode", emoji);
			LuaProtocol.Request.CODEC.encode(buffer, expected);
			require(LuaProtocol.Request.CODEC.decode(buffer).equals(expected), "Unicode/emoji/newline fidelity");
		} finally { buffer.release(); }
		rejectRequest(LuaProtocol.Action.SAVE, source + "x");
		rejectRequest(LuaProtocol.Action.SAVE_LIMITS, "x".repeat(100_001));
		rejectResponse(LuaProtocol.Action.READ, source + "x");
		rejectResponse(LuaProtocol.Action.GET_LIMITS, "x".repeat(LuaProtocol.MAX_RESPONSE_CHARS + 1));
		var decodeBuffer = buffer();
		try {
			decodeBuffer.writeLong(1).writeVarInt(LuaProtocol.Action.SAVE.ordinal()).writeUtf("oversize");
			decodeBuffer.writeUtf(source + "x", source.length() + 1);
			expectReject(() -> LuaProtocol.Request.CODEC.decode(decodeBuffer), "Oversized incoming file");
		} finally { decodeBuffer.release(); }
		require(LuaProtocol.requestTextLimit(-1) == 100_000 && LuaProtocol.responseTextLimit(99_999) == LuaProtocol.MAX_RESPONSE_CHARS, "Unknown actions remain bounded");
		require(LuaLimits.defaults().maxScriptChars() == LuaProtocol.MAX_SCRIPT_CHARS, "New install default");
		require(LuaLimits.decode("maxScriptChars=100000").maxScriptChars() == 100_000, "Preserve old configured policy");
		require(LuaLimits.decode("maxScriptChars=2048").maxScriptChars() == 2048, "Preserve custom policy");
		require(LuaLimits.decode(LuaLimits.defaults().encode()).equals(LuaLimits.defaults()), "Full limits round-trip");
		expectReject(() -> LuaLimits.decode("maxScriptChars=5000001"), "Runtime bound");
		var history = new ArrayDeque<String>();
		for (int i = 0; i < 150; i++) { history.addLast(Integer.toString(i)); LuaTextHistory.trim(history); }
		require(history.size() == 100 && history.peekFirst().equals("50") && history.peekLast().equals("149"), "Normal history keeps latest 100");
		history.clear();
		for (int i = 0; i < 100; i++) { history.addLast(source); LuaTextHistory.trim(history); }
		require(history.size() == 2 && history.stream().mapToLong(String::length).sum() <= LuaTextHistory.MAX_CHARS, "Large history bounded");
		history.addLast(emoji); LuaTextHistory.trim(history);
		require(history.peekLast().equals(emoji) && history.size() == 2, "Newest undo/redo snapshot retained");
		System.out.println("Large file smoke passed: " + checks + " checks; exact 5,000,000-char UTF-8 request/response bounds");
	}

	private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), registries); }
	private static void rejectRequest(LuaProtocol.Action action, String text) {
		var buffer = buffer();
		try { expectReject(() -> LuaProtocol.Request.CODEC.encode(buffer, new LuaProtocol.Request(1, action, "bad", text)), "Request bound " + action); }
		finally { buffer.release(); }
	}
	private static void rejectResponse(LuaProtocol.Action action, String text) {
		var buffer = buffer();
		try { expectReject(() -> LuaProtocol.Response.CODEC.encode(buffer, new LuaProtocol.Response(1, action, true, text)), "Response bound " + action); }
		finally { buffer.release(); }
	}
	private static void expectReject(Runnable action, String message) {
		try { action.run(); } catch (RuntimeException expected) { checks++; return; }
		throw new AssertionError(message + " was accepted");
	}
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
