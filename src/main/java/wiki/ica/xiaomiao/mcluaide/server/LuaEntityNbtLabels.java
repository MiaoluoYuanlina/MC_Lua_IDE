package wiki.ica.xiaomiao.mcluaide.server;

import java.util.Map;

/** Chinese label and description of the common entity NBT fields; the client translates them like every other editor text. */
final class LuaEntityNbtLabels {
	private LuaEntityNbtLabels() { }

	/** Field name -> {label, description}; unknown (modded) fields keep their own name. */
	static final Map<String, String[]> LABELS = Map.ofEntries(
		Map.entry("Motion", new String[] {"运动速度（Motion）", "实体的速度向量 [x, y, z]，单位为方块／刻；可给实体施加初速度"}),
		Map.entry("ExplosionPower", new String[] {"爆炸威力（ExplosionPower）", "火球命中时的爆炸大小，数值越大爆炸越大"}),
		Map.entry("Rotation", new String[] {"朝向（Rotation）", "实体的水平角和垂直角 [yaw, pitch]"}),
		Map.entry("Air", new String[] {"剩余氧气（Air）", "实体当前的氧气值（游戏刻），在水下会减少"}),
		Map.entry("Fire", new String[] {"着火时间（Fire）", "实体还会燃烧的游戏刻数，-1 表示未着火"}),
		Map.entry("FallDistance", new String[] {"坠落距离（FallDistance）", "已累计的坠落距离，落地时据此计算伤害"}),
		Map.entry("OnGround", new String[] {"在地面上（OnGround）", "实体当前是否站在地面上"}),
		Map.entry("PortalCooldown", new String[] {"传送门冷却（PortalCooldown）", "再次通过传送门前需要等待的游戏刻数"}),
		Map.entry("Tags", new String[] {"实体标签（Tags）", "实体的标签列表，可用于选择器 tag="}),
		Map.entry("CustomNameVisible", new String[] {"始终显示名称（CustomNameVisible）", "自定义名称是否总是显示在实体头顶"}),
		Map.entry("Silent", new String[] {"静音（Silent）", "实体是否不发出声音"}),
		Map.entry("HasVisualFire", new String[] {"视觉火焰（HasVisualFire）", "即使没有燃烧也显示火焰效果"}),
		Map.entry("TicksFrozen", new String[] {"冻结刻数（TicksFrozen）", "粉雪造成的冻结程度（游戏刻）"}),
		Map.entry("Health", new String[] {"生命值（Health）", "实体当前的生命值"}),
		Map.entry("AbsorptionAmount", new String[] {"伤害吸收（AbsorptionAmount）", "额外黄心的数值"}),
		Map.entry("HurtTime", new String[] {"受伤动画时间（HurtTime）", "受伤闪红动画剩余的游戏刻"}),
		Map.entry("DeathTime", new String[] {"死亡动画时间（DeathTime）", "死亡倒下动画已进行的游戏刻"}),
		Map.entry("Fuse", new String[] {"引信时间（Fuse）", "爆炸前剩余的游戏刻数"}),
		Map.entry("fuse", new String[] {"引信时间（fuse）", "爆炸前剩余的游戏刻数"}),
		Map.entry("ExplosionRadius", new String[] {"爆炸半径（ExplosionRadius）", "苦力怕爆炸的半径"}),
		Map.entry("powered", new String[] {"闪电苦力怕（powered）", "是否为被闪电击中的高威力苦力怕"}),
		Map.entry("ignited", new String[] {"已点燃（ignited）", "苦力怕是否已开始点燃"}),
		Map.entry("Age", new String[] {"存在时间（Age）", "实体已存在的游戏刻数"}),
		Map.entry("InLove", new String[] {"恋爱模式（InLove）", "动物继续处于恋爱状态的游戏刻数"}),
		Map.entry("ForcedAge", new String[] {"强制年龄（ForcedAge）", "幼年生物成长所需的刻数，负数表示幼年"}),
		Map.entry("CanPickUpLoot", new String[] {"可拾取物品（CanPickUpLoot）", "生物是否会拾取地上的物品"}),
		Map.entry("PersistenceRequired", new String[] {"不会自然消失（PersistenceRequired）", "生物是否不会因距离而被清除"}),
		Map.entry("LeftHanded", new String[] {"左撇子（LeftHanded）", "生物是否惯用左手"}),
		Map.entry("NoAI", new String[] {"无 AI（NoAI）", "关闭生物的 AI，使其不会移动或攻击"}),
		Map.entry("Sitting", new String[] {"坐下（Sitting）", "宠物是否坐下"}),
		Map.entry("Size", new String[] {"大小（Size）", "史莱姆或岩浆怪的体型，0 为最小"}),
		Map.entry("Sheared", new String[] {"已剪毛（Sheared）", "绵羊是否已被剪毛"}),
		Map.entry("Color", new String[] {"颜色（Color）", "染色的颜色编号"}),
		Map.entry("CollarColor", new String[] {"项圈颜色（CollarColor）", "宠物项圈的染色颜色编号"}),
		Map.entry("Angry", new String[] {"发怒（Angry）", "生物是否处于愤怒状态"}),
		Map.entry("AngerTime", new String[] {"愤怒时间（AngerTime）", "剩余的愤怒游戏刻数"}),
		Map.entry("Variant", new String[] {"变种（Variant）", "实体的外观变种"}),
		Map.entry("Tame", new String[] {"已驯服（Tame）", "是否已被驯服"}),
		Map.entry("Owner", new String[] {"主人（Owner）", "主人的 UUID"}),
		Map.entry("Leash", new String[] {"拴绳（Leash）", "拴绳的连接对象或位置"}),
		Map.entry("LifeTime", new String[] {"存在时长（LifeTime）", "烟花火箭飞行的总刻数"}),
		Map.entry("Life", new String[] {"已飞行时间（Life）", "烟花火箭已飞行的刻数"}),
		Map.entry("HasBeenShot", new String[] {"已被发射（HasBeenShot）", "是否已被发射"}),
		Map.entry("inGround", new String[] {"插在地上（inGround）", "箭是否插在方块上"}),
		Map.entry("crit", new String[] {"暴击（crit）", "箭是否带暴击效果"}),
		Map.entry("pickup", new String[] {"拾取方式（pickup）", "箭能否被玩家拾取：0 不可，1 可，2 仅创造模式"}),
		Map.entry("damage", new String[] {"伤害（damage）", "弹射物造成的基础伤害"}),
		Map.entry("Potion", new String[] {"药水（Potion）", "药水效果类型"}),
		Map.entry("Item", new String[] {"物品（Item）", "实体携带或展示的物品"}),
		Map.entry("Invulnerable", new String[] {"无敌（Invulnerable）", "实体是否不会受到伤害"}),
		Map.entry("NoGravity", new String[] {"无重力（NoGravity）", "实体是否不受重力影响"}),
		Map.entry("Glowing", new String[] {"发光（Glowing）", "实体是否显示发光轮廓"})
	);
}