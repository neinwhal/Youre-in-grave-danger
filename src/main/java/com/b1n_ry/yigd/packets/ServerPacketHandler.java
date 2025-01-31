package com.b1n_ry.yigd.packets;

import com.b1n_ry.yigd.Yigd;
import com.b1n_ry.yigd.components.GraveComponent;
import com.b1n_ry.yigd.config.ClaimPriority;
import com.b1n_ry.yigd.config.YigdConfig;
import com.b1n_ry.yigd.data.DeathInfoManager;
import com.b1n_ry.yigd.data.GraveStatus;
import com.mojang.authlib.GameProfile;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ServerPacketHandler {
    public static void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(PacketIdentifiers.GRAVE_RESTORE_C2S, (server, player, handler, buf, responseSender) -> {
            YigdConfig config = YigdConfig.getConfig();
            if (!Permissions.check(player, "yigd.command.restore", config.commandConfig.restorePermissionLevel)) {
                player.sendMessage(Text.translatable("text.yigd.command.permission_fail"));
                return;
            }

            UUID graveId = buf.readUuid();

            server.execute(() -> {
                Optional<GraveComponent> maybeComponent = DeathInfoManager.INSTANCE.getGrave(graveId);
                maybeComponent.ifPresentOrElse(component -> {
                    GameProfile owner = component.getOwner();
                    ServerPlayerEntity restoringPlayer = server.getPlayerManager().getPlayer(owner.getId());
                    if (restoringPlayer == null) {
                        player.sendMessage(Text.translatable("text.yigd.command.restore.fail.offline_player"));
                        return;
                    }

                    component.applyToPlayer(restoringPlayer, restoringPlayer.getServerWorld(), restoringPlayer.getBlockPos(), true);
                    component.setStatus(GraveStatus.CLAIMED);

                    component.removeGraveBlock();

                    player.sendMessage(Text.translatable("text.yigd.command.restore.success"));
                }, () -> player.sendMessage(Text.translatable("text.yigd.command.restore.fail")));
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(PacketIdentifiers.GRAVE_ROBBING_C2S, (server, player, handler, buf, responseSender) -> {
            YigdConfig config = YigdConfig.getConfig();
            if (!Permissions.check(player, "yigd.command.rob", config.commandConfig.robPermissionLevel)) {
                player.sendMessage(Text.translatable("text.yigd.command.permission_fail"));
                return;
            }

            UUID graveId = buf.readUuid();

            server.execute(() -> {
                Optional<GraveComponent> maybeComponent = DeathInfoManager.INSTANCE.getGrave(graveId);
                maybeComponent.ifPresentOrElse(component -> {
                    component.applyToPlayer(player, player.getServerWorld(), player.getBlockPos(), false);
                    component.setStatus(GraveStatus.CLAIMED);

                    component.removeGraveBlock();

                    player.sendMessage(Text.translatable("text.yigd.command.rob.success"));
                }, () -> player.sendMessage(Text.translatable("text.yigd.command.rob.fail")));
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(PacketIdentifiers.GRAVE_DELETE_C2S, (server, player, handler, buf, responseSender) -> {
            YigdConfig config = YigdConfig.getConfig();
            if (!Permissions.check(player, "yigd.command.delete", config.commandConfig.deletePermissionLevel)) {
                player.sendMessage(Text.translatable("text.yigd.command.permission_fail"));
                return;
            }

            UUID graveId = buf.readUuid();

            server.execute(() -> {
                ActionResult deleted = DeathInfoManager.INSTANCE.delete(graveId);
                DeathInfoManager.INSTANCE.markDirty();

                String translatable = switch (deleted) {
                    case SUCCESS -> "text.yigd.command.delete.success";
                    case PASS -> "text.yigd.command.delete.pass";
                    case FAIL -> "text.yigd.command.delete.fail";
                    default -> "If you see this, congratulations. You've broken YIGD";
                };
                player.sendMessage(Text.translatable(translatable));
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(PacketIdentifiers.GRAVE_LOCKING_C2S, (server, player, handler, buf, responseSender) -> {
            YigdConfig config = YigdConfig.getConfig();
            if (!Permissions.check(player, "yigd.command.locking", config.commandConfig.unlockPermissionLevel)) {
                player.sendMessage(Text.translatable("text.yigd.command.permission_fail"));
                return;
            }

            UUID graveId = buf.readUuid();

            boolean lockState = buf.readBoolean();
            server.execute(() -> {
                Optional<GraveComponent> component = DeathInfoManager.INSTANCE.getGrave(graveId);
                component.ifPresentOrElse(grave -> grave.setLocked(lockState),
                        () -> player.sendMessage(Text.translatable("text.yigd.command.lock.fail")));
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(PacketIdentifiers.GRAVE_OVERVIEW_REQUEST_C2S, (server, player, handler, buf, responseSender) -> {
            YigdConfig config = YigdConfig.getConfig();
            if (!Permissions.check(player, "yigd.command.view_self", config.commandConfig.viewSelfPermissionLevel)) {
                player.sendMessage(Text.translatable("text.yigd.command.permission_fail"));
                return;
            }

            UUID graveId = buf.readUuid();
            Optional<GraveComponent> component = DeathInfoManager.INSTANCE.getGrave(graveId);
            component.ifPresentOrElse(grave -> sendGraveOverviewPacket(player, grave),
                    () -> player.sendMessage(Text.translatable("text.yigd.command.view_self.fail")));
        });
        ServerPlayNetworking.registerGlobalReceiver(PacketIdentifiers.GRAVE_SELECT_REQUEST_C2S, (server, player, handler, buf, responseSender) -> {
            YigdConfig config = YigdConfig.getConfig();
            if (!Permissions.check(player, "yigd.command.view_user", config.commandConfig.viewUserPermissionLevel)) {
                player.sendMessage(Text.translatable("text.yigd.command.permission_fail"));
                return;
            }

            GameProfile profile = buf.readGameProfile();
            List<GraveComponent> components = DeathInfoManager.INSTANCE.getBackupData(profile);

            List<LightGraveData> lightGraveData = new ArrayList<>();
            for (GraveComponent component : components) {
                lightGraveData.add(component.toLightData());
            }

            sendGraveSelectionPacket(player, profile, lightGraveData);
        });
        ServerPlayNetworking.registerGlobalReceiver(PacketIdentifiers.CONFIG_UPDATE_C2S, (server, player, handler, buf, responseSender) -> {
            ClaimPriority claimPriority = buf.readEnumConstant(ClaimPriority.class);
            ClaimPriority robPriority = buf.readEnumConstant(ClaimPriority.class);

            UUID playerId = player.getUuid();
            Yigd.CLAIM_PRIORITIES.put(playerId, claimPriority);
            Yigd.ROB_PRIORITIES.put(playerId, robPriority);

            Yigd.LOGGER.info("Priority overwritten for player %s. Claiming: %s / Robbing: %s".formatted(player.getGameProfile().getName(), claimPriority.name(), robPriority.name()));
        });
    }

    public static void sendGraveOverviewPacket(ServerPlayerEntity player, GraveComponent component) {
        YigdConfig.CommandConfig config = YigdConfig.getConfig().commandConfig;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeNbt(component.toNbt());
        buf.writeBoolean(Permissions.check(player, "yigd.command.restore", config.restorePermissionLevel));
        buf.writeBoolean(Permissions.check(player, "yigd.command.rob", config.robPermissionLevel));
        buf.writeBoolean(Permissions.check(player, "yigd.command.delete", config.deletePermissionLevel));
        buf.writeBoolean(Permissions.check(player, "yigd.command.locking", config.unlockPermissionLevel));

        ServerPlayNetworking.send(player, PacketIdentifiers.GRAVE_OVERVIEW_S2C, buf);
    }

    public static void sendGraveSelectionPacket(ServerPlayerEntity player, GameProfile ofUser, List<LightGraveData> data) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(data.size());

        for (LightGraveData grave : data) {
            buf.writeNbt(grave.toNbt());
        }
        buf.writeGameProfile(ofUser);

        ServerPlayNetworking.send(player, PacketIdentifiers.GRAVE_SELECTION_S2C, buf);
    }

    public static void sendPlayerSelectionPacket(ServerPlayerEntity player, List<LightPlayerData> data) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(data.size());

        for (LightPlayerData playerData : data) {
            buf.writeNbt(playerData.toNbt());
        }

        ServerPlayNetworking.send(player, PacketIdentifiers.PLAYER_SELECTION_S2C, buf);
    }
}
