package wiki.ica.xiaomiao.mcluaide.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Handles resource-pack queries locally; the server cannot inspect this client-owned state directly. */
final class LuaClientResourcePackHandler {
	private LuaClientResourcePackHandler() {
	}

	static void handle(Minecraft client, LuaProtocol.ClientResourcePackRequest request) {
		LuaProtocol.ResourcePackAction action;
		try {
			action = LuaProtocol.ResourcePackAction.byId(request.actionId());
		} catch (IllegalArgumentException error) {
			reply(request.requestId(), false, false, "未知材质包操作");
			return;
		}
		PackRepository repository = client.getResourcePackRepository();
		repository.reload();
		Pack pack = findPack(repository.getAvailablePacks(), request.packName());
		if (action == LuaProtocol.ResourcePackAction.HAS) {
			boolean enabled = pack != null && repository.getSelectedIds().contains(pack.getId());
			reply(request.requestId(), true, enabled, pack == null ? "未找到材质包" : pack.getId());
			return;
		}
		if (pack == null) {
			reply(request.requestId(), false, false, "客户端未找到材质包：“" + request.packName() + "”");
			return;
		}
		if (repository.getSelectedIds().contains(pack.getId())) {
			reply(request.requestId(), true, true, pack.getId());
			return;
		}
		List<String> selected = new ArrayList<>(repository.getSelectedIds());
		selected.add(pack.getId());
		repository.setSelected(selected);
		client.options.updateResourcePacks(repository);
		client.options.save();
		client.reloadResourcePacks().whenComplete((ignored, failure) -> client.execute(() -> {
			if (failure == null) reply(request.requestId(), true, true, pack.getId());
			else {
				McLuaIdeMod.LOGGER.warn("Failed to enable client resource pack {}", pack.getId(), failure);
				reply(request.requestId(), false, false, "材质包重载失败：" + safeMessage(failure));
			}
		}));
	}

	private static Pack findPack(Collection<Pack> packs, String rawName) {
		String expected = normalized(rawName);
		List<Pack> exact = packs.stream().filter(pack -> normalized(pack.getId()).equals(expected)
				|| normalized(pack.getTitle().getString()).equals(expected)).toList();
		if (exact.size() == 1) return exact.getFirst();
		List<Pack> loose = packs.stream().filter(pack -> normalized(pack.getId()).endsWith(expected)
				|| normalized(pack.getTitle().getString()).contains(expected)).toList();
		return loose.size() == 1 ? loose.getFirst() : null;
	}

	private static String normalized(String value) {
		String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace('\\', '/');
		if (normalized.startsWith("file/")) normalized = normalized.substring(5);
		if (normalized.endsWith(".zip")) normalized = normalized.substring(0, normalized.length() - 4);
		return normalized;
	}

	private static void reply(long requestId, boolean success, boolean enabled, String message) {
		if (!ClientPlayNetworking.canSend(LuaProtocol.ClientResourcePackResponse.TYPE)) return;
		ClientPlayNetworking.send(new LuaProtocol.ClientResourcePackResponse(
				requestId, success, enabled, message == null ? "" : message));
	}

	private static String safeMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}
}
