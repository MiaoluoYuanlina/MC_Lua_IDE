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
		// NBT fields keep the type the entity saved: Motion is a list of doubles, ExplosionPower a byte.
		var motionTemplate = new net.minecraft.nbt.ListTag(); motionTemplate.add(net.minecraft.nbt.DoubleTag.valueOf(0));
		var motion = LuaEntityNbtConfig.convert(com.google.gson.JsonParser.parseString("[1.0, 0, 0.5]"), motionTemplate, "Motion");
		require(motion.toString().equals("[1.0d,0.0d,0.5d]"), "Motion stays doubles: " + motion); checks++;
		var power = LuaEntityNbtConfig.convert(com.google.gson.JsonParser.parseString("3"), net.minecraft.nbt.ByteTag.valueOf((byte) 1), "ExplosionPower");
		require(power instanceof net.minecraft.nbt.ByteTag byteTag && byteTag.byteValue() == 3, "ExplosionPower stays a byte: " + power); checks++;
		try { LuaEntityNbtConfig.convert(com.google.gson.JsonParser.parseString("300"), net.minecraft.nbt.ByteTag.valueOf((byte) 1), "ExplosionPower"); throw new AssertionError("byte overflow accepted"); }
		catch (IllegalArgumentException expected) { checks++; }
		var plain = LuaEntityNbtConfig.convert(com.google.gson.JsonParser.parseString("{\"a\":2,\"b\":1.5,\"c\":true,\"d\":\"x\"}"), null, "root");
		require(plain.toString().contains("a:2") && plain.toString().contains("b:1.5d") && plain.toString().contains("c:1b") && plain.toString().contains("d:\"x\""), "untyped values: " + plain); checks++;
		require(LuaEntityNbtLabels.LABELS.containsKey("Motion") && LuaEntityNbtLabels.LABELS.containsKey("ExplosionPower") && LuaEntityNbtLabels.LABELS.size() > 40, "NBT field labels"); checks++;
		System.out.println("Entity component support smoke passed: " + checks + " checks");
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
