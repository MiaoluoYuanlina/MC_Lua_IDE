package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.level.validation.DirectoryValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Verifies that Minecraft 26.1.2 recognizes both generated pack metadata formats. */
public final class ManagedPackSmoke {
	private ManagedPackSmoke() {
	}

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		check(PackType.SERVER_DATA);
		check(PackType.CLIENT_RESOURCES);
		checkExport();
		System.out.println("Managed data/resource pack metadata smoke test passed");
	}

	private static void check(PackType type) throws Exception {
		Path parent = Files.createTempDirectory("mc-lua-pack-smoke-");
		Path pack = parent.resolve("mc_lua_ide_generated");
		try {
			LuaManagedPackApi.writeMetadata(pack, type);
			PackRepository repository = new PackRepository(new FolderRepositorySource(parent, type,
					PackSource.WORLD, new DirectoryValidator(path -> false)));
			repository.reload();
			if (!repository.isAvailable("file/mc_lua_ide_generated")) {
				throw new AssertionError("Minecraft did not recognize generated " + type + " pack: "
						+ repository.getAvailableIds());
			}
		} finally {
			Files.deleteIfExists(pack.resolve("pack.mcmeta"));
			Files.deleteIfExists(pack);
			Files.deleteIfExists(parent);
		}
	}

	private static void checkExport() throws Exception {
		Path parent = Files.createTempDirectory("mc-lua-pack-export-");
		Path root = parent.resolve("mc_lua_ide_generated_resources");
		Path asset = root.resolve("assets/example/items/test.json");
		Path zip = parent.resolve("mc_lua_ide_generated_resources.zip");
		try {
			Files.createDirectories(asset.getParent());
			Files.writeString(asset, "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/diamond\"}}");
			if (!LuaManagedPackApi.exportAssetFolder(root).equals(zip))
				throw new AssertionError("Asset ZIP was exported to an unexpected path");
			try (ZipFile contents = new ZipFile(zip.toFile())) {
				if (contents.getEntry("pack.mcmeta") == null
						|| contents.getEntry("assets/example/items/test.json") == null)
					throw new AssertionError("Exported resource pack is missing metadata or item JSON");
			}
		} finally {
			Files.deleteIfExists(zip);
			Files.deleteIfExists(asset);
			Files.deleteIfExists(asset.getParent());
			Files.deleteIfExists(asset.getParent().getParent());
			Files.deleteIfExists(asset.getParent().getParent().getParent());
			Files.deleteIfExists(root.resolve("pack.mcmeta"));
			Files.deleteIfExists(root);
			Files.deleteIfExists(parent);
		}
	}
}
