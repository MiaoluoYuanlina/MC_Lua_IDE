package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.compat.ToolCompatibilityFilter;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.compat.ToolDescriptor;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Tool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolRegistry;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.advancement.AdvancementTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.block.BlockTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.blockentity.BlockEntityTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.bossbar.BossbarTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.command.CommandTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.contentregistry.ContentRegistryTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.data.DataTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.datapack.DatapackTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.entity.EntityTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.events.EventsTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.fluidstorage.FluidStorageTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.function.FunctionTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.inventory.InventoryTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.itemmodify.ItemModifyTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.itemstack.ItemStackTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelCreateExplosionTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelGetBiomeAtTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelPoiQueryTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelGetDifficultyTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelGetDimensionInfoTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelGetGameRuleTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelGetInfoTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelGetSpawnPointTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelGetTimeTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelGetWeatherTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelFillBiomeTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelLightningStrikeTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelListBiomesInDimensionTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelListDimensionsTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelListGameRulesTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelGetMobSpawnsTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelPlaceFeatureTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelPlaceFeaturesBatchTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelPlaySoundTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelSetDifficultyTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelSetGameRuleTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelSetSpawnPointTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelSetTimeTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelSetWeatherTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level.LevelSpawnParticleTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.player.PlayerTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.playerscreen.PlayerScreenTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.registry.RegistryAccessTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.resourcecondition.ResourceConditionTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.schedule.ScheduleTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.scoreboard.ScoreboardTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.server.ServerGetMotdTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.server.ServerGetStatusTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.server.ServerReloadResourcesTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.server.ServerSaveAllWorldsTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.server.ServerSetMotdTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.structure.StructureTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.worldborder.WorldborderTools;

/**
 * Central enumeration of every tool class. Each entry is filtered through
 * {@link ToolCompatibilityFilter}; surviving entries are instantiated and registered
 * into the {@link ToolRegistry}.
 *
 * <p>Add a new tool: create the class with {@code @McpTool}, then add it to
 * {@link #ALL_TOOL_CLASSES}. There is no classpath scan — explicit listing keeps the
 * full surface visible from a single file.
 */
public final class ToolRegistration {

    private static final Logger LOGGER = LoggerFactory.getLogger("minecraft_fabric_mcp/tools");

    private ToolRegistration() {}

    /** The full universe of tool classes. Ordered for stable registration logs. */
    public static final List<Class<? extends Tool>> ALL_TOOL_CLASSES =
            List.of(
                    // server
                    ServerGetStatusTool.class,
                    ServerGetMotdTool.class,
                    ServerSetMotdTool.class,
                    ServerSaveAllWorldsTool.class,
                    ServerReloadResourcesTool.class,
                    // level
                    LevelListDimensionsTool.class,
                    LevelGetDimensionInfoTool.class,
                    LevelGetInfoTool.class,
                    LevelGetTimeTool.class,
                    LevelSetTimeTool.class,
                    LevelGetWeatherTool.class,
                    LevelSetWeatherTool.class,
                    LevelGetDifficultyTool.class,
                    LevelSetDifficultyTool.class,
                    LevelGetSpawnPointTool.class,
                    LevelSetSpawnPointTool.class,
                    LevelPlaySoundTool.class,
                    LevelSpawnParticleTool.class,
                    LevelLightningStrikeTool.class,
                    LevelCreateExplosionTool.class,
                    LevelGetGameRuleTool.class,
                    LevelSetGameRuleTool.class,
                    LevelListGameRulesTool.class,
                    LevelGetBiomeAtTool.class,
                    LevelPoiQueryTool.class,
                    LevelListBiomesInDimensionTool.class,
                    LevelPlaceFeatureTool.class,
                    LevelGetMobSpawnsTool.class,
                    LevelPlaceFeaturesBatchTool.class,
                    LevelFillBiomeTool.class,
                    // block
                    BlockTools.GetState.class,
                    BlockTools.SetState.class,
                    BlockTools.FillRegion.class,
                    BlockTools.CloneRegion.class,
                    BlockTools.ReplaceInRegion.class,
                    BlockTools.GetTopY.class,
                    BlockTools.ScanRegion.class,
                    BlockTools.FillBatch.class,
                    BlockTools.FillColumns.class,
                    BlockTools.FillColumnsStrata.class,
                    BlockTools.ErodeRegion.class,
                    BlockTools.HydraulicErodeStart.class,
                    BlockTools.HydraulicErodeStatus.class,
                    BlockTools.HydraulicErodeResult.class,
                    BlockTools.ScanSummary.class,
                    BlockTools.GetMapColor.class,
                    BlockTools.TransformPreview.class,
                    BlockTools.RenderRegion.class,
                    // block entity
                    BlockEntityTools.GetNbt.class,
                    BlockEntityTools.SetNbt.class,
                    BlockEntityTools.ClearInventory.class,
                    // entity
                    EntityTools.Summon.class,
                    EntityTools.Get.class,
                    EntityTools.Query.class,
                    EntityTools.GetComponents.class,
                    EntityTools.GetNbt.class,
                    EntityTools.SetNbt.class,
                    EntityTools.Teleport.class,
                    EntityTools.ApplyDamage.class,
                    EntityTools.SetVelocity.class,
                    EntityTools.ApplyEffect.class,
                    EntityTools.RemoveEffect.class,
                    EntityTools.GetEffects.class,
                    EntityTools.Kill.class,
                    EntityTools.Despawn.class,
                    EntityTools.AddTag.class,
                    EntityTools.RemoveTag.class,
                    EntityTools.ListTags.class,
                    // player
                    PlayerTools.ListOnline.class,
                    PlayerTools.GetInfo.class,
                    PlayerTools.GetInventory.class,
                    PlayerTools.GiveItem.class,
                    PlayerTools.ClearSlot.class,
                    PlayerTools.ClearAll.class,
                    PlayerTools.SetGamemode.class,
                    PlayerTools.Kick.class,
                    PlayerTools.SendMessage.class,
                    PlayerTools.SendActionbar.class,
                    PlayerTools.SendTitle.class,
                    PlayerTools.PlaySound.class,
                    PlayerTools.SetSpawn.class,
                    PlayerTools.GrantXp.class,
                    PlayerTools.SetXpLevel.class,
                    PlayerTools.SetCamera.class,
                    // inventory
                    InventoryTools.Get.class,
                    InventoryTools.SetSlot.class,
                    InventoryTools.ClearSlot.class,
                    InventoryTools.SwapSlots.class,
                    InventoryTools.CountItems.class,
                    // itemstack
                    ItemStackTools.Describe.class,
                    ItemStackTools.DropAt.class,
                    // command
                    CommandTools.Execute.class,
                    CommandTools.ExecuteAs.class,
                    CommandTools.Register.class,
                    // scoreboard
                    ScoreboardTools.ListObjectives.class,
                    ScoreboardTools.GetObjective.class,
                    ScoreboardTools.AddObjective.class,
                    ScoreboardTools.RemoveObjective.class,
                    ScoreboardTools.SetDisplaySlot.class,
                    ScoreboardTools.GetScore.class,
                    ScoreboardTools.SetScore.class,
                    ScoreboardTools.AddScore.class,
                    ScoreboardTools.ResetParticipant.class,
                    ScoreboardTools.ListTeams.class,
                    ScoreboardTools.AddTeam.class,
                    ScoreboardTools.RemoveTeam.class,
                    ScoreboardTools.TeamAddMember.class,
                    ScoreboardTools.TeamRemoveMember.class,
                    // data
                    DataTools.StorageGet.class,
                    DataTools.StorageSet.class,
                    DataTools.StorageRemove.class,
                    DataTools.StorageListNamespaces.class,
                    DataTools.AttachmentGet.class,
                    DataTools.AttachmentSet.class,
                    DataTools.AttachmentRemove.class,
                    DataTools.AttachmentListKeys.class,
                    // structure
                    StructureTools.SaveFromWorld.class,
                    StructureTools.LoadToWorld.class,
                    StructureTools.ListAll.class,
                    StructureTools.GetInfo.class,
                    StructureTools.Delete.class,
                    StructureTools.FileRead.class,
                    StructureTools.FileWrite.class,
                    StructureTools.FileList.class,
                    StructureTools.FileDelete.class,
                    // datapack
                    DatapackTools.ListAvailable.class,
                    DatapackTools.ListEnabled.class,
                    DatapackTools.Enable.class,
                    DatapackTools.Disable.class,
                    // loot/recipe/tag/resource
                    RegistryAccessTools.LootList.class,
                    RegistryAccessTools.LootGetDef.class,
                    RegistryAccessTools.LootGenerate.class,
                    RegistryAccessTools.RecipeList.class,
                    RegistryAccessTools.RecipeGetDef.class,
                    RegistryAccessTools.RecipeFindResult.class,
                    RegistryAccessTools.RecipeFindIngredient.class,
                    RegistryAccessTools.TagListInRegistry.class,
                    RegistryAccessTools.TagGetMembers.class,
                    RegistryAccessTools.TagCheckMembership.class,
                    RegistryAccessTools.BlockFamilyVariants.class,
                    RegistryAccessTools.ResourceListNs.class,
                    RegistryAccessTools.ResourceGet.class,
                    // events
                    EventsTools.Subscribe.class,
                    EventsTools.Poll.class,
                    EventsTools.ListSubscriptions.class,
                    EventsTools.Unsubscribe.class,
                    // advancement
                    AdvancementTools.Grant.class,
                    AdvancementTools.Revoke.class,
                    AdvancementTools.ListPlayer.class,
                    AdvancementTools.ListAll.class,
                    AdvancementTools.GetDefinition.class,
                    // bossbar
                    BossbarTools.ListAll.class,
                    BossbarTools.Add.class,
                    BossbarTools.Remove.class,
                    BossbarTools.Get.class,
                    BossbarTools.SetValue.class,
                    BossbarTools.SetMax.class,
                    BossbarTools.SetName.class,
                    BossbarTools.SetColor.class,
                    BossbarTools.SetStyle.class,
                    BossbarTools.SetVisible.class,
                    BossbarTools.SetPlayers.class,
                    // content registry
                    ContentRegistryTools.GetFuel.class,
                    ContentRegistryTools.SetFuel.class,
                    ContentRegistryTools.IsFlammableBlock.class,
                    ContentRegistryTools.SetFlammableBlock.class,
                    ContentRegistryTools.IsCompostable.class,
                    ContentRegistryTools.SetCompostable.class,
                    // fluid storage
                    FluidStorageTools.Get.class,
                    FluidStorageTools.ListAt.class,
                    // function
                    FunctionTools.Run.class,
                    FunctionTools.ListAll.class,
                    FunctionTools.GetDefinition.class,
                    // item modify
                    ItemModifyTools.EntitySlot.class,
                    ItemModifyTools.BlockSlot.class,
                    // player screen
                    PlayerScreenTools.OpenMenu.class,
                    PlayerScreenTools.OpenContainer.class,
                    PlayerScreenTools.Close.class,
                    // resource condition
                    ResourceConditionTools.Evaluate.class,
                    // schedule
                    ScheduleTools.FunctionTool.class,
                    ScheduleTools.Clear.class,
                    ScheduleTools.ListAll.class,
                    // worldborder
                    WorldborderTools.Get.class,
                    WorldborderTools.SetSize.class,
                    WorldborderTools.AddSize.class,
                    WorldborderTools.SetCenter.class,
                    WorldborderTools.SetWarningBlocks.class,
                    WorldborderTools.SetWarningTime.class,
                    WorldborderTools.SetDamageAmount.class,
                    WorldborderTools.SetDamageBuffer.class);

    /**
     * Build and populate a {@link ToolRegistry} for the current Minecraft target.
     * Reports the registration outcome (how many tools registered, how many filtered
     * out by version/module constraints) at INFO so the boot log is informative.
     */
    public static ToolRegistry buildRegistry(ToolCompatibilityFilter filter) {
        return buildRegistry(ALL_TOOL_CLASSES, filter);
    }

    /**
     * Build a {@link ToolRegistry} from an explicit list of tool classes. Used by the server
     * entrypoint with {@link #ALL_TOOL_CLASSES} and by the client entrypoint
     * ({@code ClientToolRegistration}) with the client-only tool list, so both surfaces share the
     * same compatibility/category filtering loop.
     */
    public static ToolRegistry buildRegistry(
            List<Class<? extends Tool>> toolClasses, ToolCompatibilityFilter filter) {
        ToolRegistry registry = new ToolRegistry();
        List<String> skipped = new ArrayList<>();
        for (Class<? extends Tool> klass : toolClasses) {
            Optional<ToolDescriptor> descOpt = filter.evaluate(klass);
            if (descOpt.isEmpty()) {
                skipped.add(klass.getSimpleName());
                continue;
            }
            try {
                Tool tool = klass.getDeclaredConstructor().newInstance();
                registry.register(descOpt.get(), tool);
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("Failed to instantiate tool '{}': {}", klass.getName(), e.getMessage(), e);
            }
        }
        LOGGER.info(
                "Registered {} MCP tools ({} skipped due to version/module/category constraints)",
                registry.size(),
                skipped.size());
        return registry;
    }
}
