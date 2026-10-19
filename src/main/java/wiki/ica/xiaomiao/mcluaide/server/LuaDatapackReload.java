package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.server.MinecraftServer;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/** Equivalent to vanilla /reload discovery, but returns its actual completion to the IDE. */
final class LuaDatapackReload {
	private static final Map<MinecraftServer, CompletableFuture<String>> ACTIVE = new WeakHashMap<>();
	private LuaDatapackReload() { }
	static CompletableFuture<String> reload(MinecraftServer server) {
		if (!server.isSameThread()) throw new IllegalStateException("数据包重载必须从服务器线程发起");
		CompletableFuture<String> current = ACTIVE.get(server);
		if (current != null && !current.isDone()) return current;
		var repository = server.getPackRepository();
		var selected = new ArrayList<>(repository.getSelectedIds());
		var disabled = server.getWorldData().getDataConfiguration().dataPacks().getDisabled();
		repository.reload();
		for (String id : repository.getAvailableIds()) if (!selected.contains(id) && !disabled.contains(id)) selected.add(id);
		CompletableFuture<String> future = server.reloadResources(selected)
				.thenApply(ignored -> "数据包重载完成，插入 ID 列表已更新；维度/世界生成等注册表修改可能需要重进存档");
		ACTIVE.put(server, future);
		return future;
	}
}
