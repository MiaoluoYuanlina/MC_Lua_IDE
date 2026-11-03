package wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto;

import java.util.List;

/** Result of generating a loot table without actually dropping items. */
public record LootDropInfo(List<ItemStackInfo> drops) {

    public LootDropInfo {
        drops = drops == null ? List.of() : List.copyOf(drops);
    }
}
