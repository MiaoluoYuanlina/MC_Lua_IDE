package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which data components an entity class answers for. An entity exposes a component by comparing the requested type with
 * {@code DataComponents.X} inside its {@code get} and {@code applyImplicitComponent} methods (cat variant, sheep colour, ...).
 * Reading only the values an entity currently returns misses every component whose value is absent right now, so the class
 * hierarchy is scanned for those references; modded entities are covered the same way.
 */
final class LuaEntityComponentSupport {
	private static final String COMPONENTS = "net/minecraft/core/component/DataComponents";
	private static final Map<Class<?>, Set<String>> CACHE = new ConcurrentHashMap<>();

	private LuaEntityComponentSupport() { }

	/** Component ids (e.g. {@code minecraft:cat/variant}) that the entity's class or a superclass handles. */
	static Set<String> supported(Entity entity) {
		if (entity == null) return Set.of();
		return supported(entity.getClass());
	}

	static Set<String> supported(Class<?> entityClass) {
		Set<String> result = new HashSet<>();
		for (Class<?> type = entityClass; type != null && Entity.class.isAssignableFrom(type); type = type.getSuperclass())
			result.addAll(CACHE.computeIfAbsent(type, LuaEntityComponentSupport::scan));
		return result;
	}

	private static Set<String> scan(Class<?> type) {
		Set<String> found = new HashSet<>();
		String resource = type.getName().replace('.', '/') + ".class";
		try (InputStream input = type.getClassLoader() == null ? ClassLoader.getSystemResourceAsStream(resource) : type.getClassLoader().getResourceAsStream(resource)) {
			if (input == null) return Set.of();
			new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					boolean relevant = name.equals("get") && descriptor.startsWith("(Lnet/minecraft/core/component/DataComponentType;)")
							|| name.equals("applyImplicitComponent") && descriptor.startsWith("(Lnet/minecraft/core/component/DataComponentType;");
					if (!relevant) return null;
					return new MethodVisitor(Opcodes.ASM9) {
						@Override public void visitFieldInsn(int opcode, String owner, String field, String fieldDescriptor) {
							if (opcode != Opcodes.GETSTATIC || !owner.equals(COMPONENTS)) return;
							try {
								Object value = DataComponents.class.getField(field).get(null);
								if (value instanceof DataComponentType<?> component && !component.isTransient()) {
									var id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component);
									if (id != null) found.add(id.toString());
								}
							} catch (ReflectiveOperationException | RuntimeException ignored) {
								// A constant that cannot be read is simply not offered.
							}
						}
					};
				}
			}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (java.io.IOException | RuntimeException failure) {
			return Collections.emptySet();
		}
		return Set.copyOf(found);
	}
}
