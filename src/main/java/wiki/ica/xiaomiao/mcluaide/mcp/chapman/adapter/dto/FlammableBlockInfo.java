package wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto;

/** Fire-propagation parameters registered for a block via Fabric's FlammableBlockRegistry. */
public record FlammableBlockInfo(boolean flammable, int spreadChance, int burnChance) {

    public static FlammableBlockInfo notFlammable() {
        return new FlammableBlockInfo(false, 0, 0);
    }
}
