package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.Set;

/** Entity classes are scanned for the data components they answer for, even while the value is absent. */
public final class EntityComponentSupportSmoke {
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		int checks = 0;
		Set<String> cat = LuaEntityComponentSupport.supported(Class.forName("net.minecraft.world.entity.animal.feline.Cat"));
		require(cat.contains("minecraft:cat/variant") && cat.contains("minecraft:cat/collar"), "Cat components: " + cat); checks++;
		Set<String> sheep = LuaEntityComponentSupport.supported(Class.forName("net.minecraft.world.entity.animal.sheep.Sheep"));
		require(sheep.contains("minecraft:sheep/color"), "Sheep components: " + sheep); checks++;
		Set<String> axolotl = LuaEntityComponentSupport.supported(Class.forName("net.minecraft.world.entity.animal.axolotl.Axolotl"));
		require(axolotl.contains("minecraft:axolotl/variant"), "Axolotl components: " + axolotl); checks++;
		require(!sheep.contains("minecraft:cat/variant") && !cat.contains("minecraft:sheep/color"), "Components do not leak between unrelated entities"); checks++;
		require(LuaEntityComponentSupport.supported((net.minecraft.world.entity.Entity) null).isEmpty(), "No entity, no components"); checks++;
		System.out.println("Entity component support smoke passed: " + checks + " checks");
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
