package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;

/** A vanilla chest protocol/menu: ordinary clients need no mod or custom screen. */
public final class LuaVirtualChestMenu extends ChestMenu {
    final ServerScriptManager.ScriptExecution owner;
    final Component title;
    public final boolean readOnly;

    LuaVirtualChestMenu(int id, Inventory inventory, Container contents, int rows, Component title,
                        boolean readOnly, ServerScriptManager.ScriptExecution owner) {
        super(switch (rows) {
            case 1 -> MenuType.GENERIC_9x1; case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3; case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5; default -> MenuType.GENERIC_9x6;
        }, id, inventory, contents, rows);
        this.title = title; this.readOnly = readOnly; this.owner = owner;
    }

    @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (!readOnly) { super.clicked(slot, button, input, player); return; }
        // Block every movement route, including shift/number/drop/creative clone/drag/double-click.
        // Do not fake a successful take event: the separate click event drives safe shop actions.
        // The authenticated server packet hook reports this click without pretending items moved.
    }
}
