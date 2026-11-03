package wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto;

/** A pending entry in the {@code MinecraftServer#getFunctions()} scheduler. */
public record ScheduledFunctionInfo(String functionId, long ticksRemaining) {}
