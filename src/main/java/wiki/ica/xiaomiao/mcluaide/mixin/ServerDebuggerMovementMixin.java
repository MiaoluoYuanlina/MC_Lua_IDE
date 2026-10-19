package wiki.ica.xiaomiao.mcluaide.mixin;

import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.server.LuaDebuggerGamePause;

/** Run after vanilla's thread check, never on the Netty thread. Do not suspend networking/player ticks. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerDebuggerMovementMixin {
	private static final String THREAD_CHECK = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V";
	@Shadow public ServerPlayer player;
	@Shadow private boolean clientIsFloating;
	@Shadow private boolean clientVehicleIsFloating;

	@Inject(method = "tick", at = @At("HEAD"))
	private void debuggerAnchor(CallbackInfo ci) {
		if (!LuaDebuggerGamePause.movementLocked(player)) return;
		player.setLastClientInput(Input.EMPTY);
		player.setDeltaMovement(Vec3.ZERO);
		clientIsFloating = false; clientVehicleIsFloating = false;
	}
	@Inject(method = "handlePlayerInput", at = @At(value = "INVOKE", target = THREAD_CHECK, shift = At.Shift.AFTER), cancellable = true)
	private void debuggerInput(ServerboundPlayerInputPacket packet, CallbackInfo ci) {
		if (LuaDebuggerGamePause.movementLocked(player)) { player.setLastClientInput(Input.EMPTY); ci.cancel(); }
	}
	@Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = THREAD_CHECK, shift = At.Shift.AFTER), cancellable = true)
	private void debuggerMovement(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		if (!LuaDebuggerGamePause.movementLocked(player)) return;
		float yaw = packet.getYRot(player.getYRot()), pitch = packet.getXRot(player.getXRot());
		if (Float.isFinite(yaw) && Float.isFinite(pitch)) {
			player.setYRot(yaw); player.setXRot(Math.clamp(pitch, -90, 90)); player.setYHeadRot(yaw);
		}
		Vec3 requested = new Vec3(packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ()));
		if (requested.distanceToSqr(player.position()) > 0.000001)
			player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
		ci.cancel();
	}
	@Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE", target = THREAD_CHECK, shift = At.Shift.AFTER), cancellable = true)
	private void debuggerVehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
		if (!LuaDebuggerGamePause.movementLocked(player)) return;
		var vehicle = player.getRootVehicle();
		if (vehicle != player) player.connection.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));
		ci.cancel();
	}
	@Inject(method = "handlePaddleBoat", at = @At(value = "INVOKE", target = THREAD_CHECK, shift = At.Shift.AFTER), cancellable = true)
	private void debuggerBoat(ServerboundPaddleBoatPacket packet, CallbackInfo ci) {
		if (LuaDebuggerGamePause.movementLocked(player)) ci.cancel();
	}
}
