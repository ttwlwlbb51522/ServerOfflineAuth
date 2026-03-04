package cn.citprobe.ServerOfflineAuth;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ServerOfflineAuth.MODID)
public class AuthEvents {

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (player.getServer() != null && player.getServer().isSingleplayer()) {
                LoginManager.recordJoinTime(player);
                LoginManager.setAuthenticated(player, true);
                player.sendSystemMessage(Component.translatable("auth.message.singleplayer_auto_login").withStyle(ChatFormatting.GREEN));
                return;
            }

            LoginManager.recordJoinTime(player);
            LoginManager.backupPlayerState(player);
            player.setGameMode(GameType.ADVENTURE);

            if (Config.SERVER.joinMessageEnabled.get()) {
                if (!StorageManager.hasPlayerData(player.getUUID())) {
                    player.sendSystemMessage(Component.translatable("auth.message.join.need_register").withStyle(ChatFormatting.RED));
                } else {
                    player.sendSystemMessage(Component.translatable("auth.message.join.need_login").withStyle(ChatFormatting.RED));
                }
            }
            LoginManager.setAuthenticated(player, false);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (LoginManager.isAuthenticated(player)) {
                PlayerData data = StorageManager.getPlayerData(player.getUUID());
                if (data != null) {
                    data.setPlayerName(player.getName().getString());
                    data.setLastDimension(player.level().dimension().location().toString());
                    data.setLastX(player.getX());
                    data.setLastY(player.getY());
                    data.setLastZ(player.getZ());
                    data.setLastYRot(player.getYRot());
                    data.setLastXRot(player.getXRot());
                    data.setGameMode(player.gameMode.getGameModeForPlayer().getId());
                    data.setMayFly(player.getAbilities().mayfly);
                    data.setFlying(player.getAbilities().flying);
                    StorageManager.putPlayerData(player.getUUID(), data);
                }
            }
            LoginManager.removePlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!LoginManager.isAuthenticated(player)) {
                player.setGameMode(GameType.ADVENTURE);

                if (!player.onGround() && !player.getAbilities().mayfly) {
                    player.getAbilities().mayfly = true;
                    player.getAbilities().flying = false;
                    player.onUpdateAbilities();
                }

                if (player.getServer() != null) {
                    var spawnPos = player.getServer().overworld().getSharedSpawnPos();
                    player.teleportTo(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
                } else {
                    ServerOfflineAuth.LOGGER.warn("服务器实例为 null，无法传送玩家 {}", player.getName().getString());
                }
                player.setDeltaMovement(0, 0, 0);
                player.hurtMarked = true;
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !LoginManager.isAuthenticated(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && !LoginManager.isAuthenticated((ServerPlayer) player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && !LoginManager.isAuthenticated(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player && !LoginManager.isAuthenticated(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player && !LoginManager.isAuthenticated(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerAttack(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && !LoginManager.isAuthenticated(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        if (!LoginManager.isAuthenticated(event.getPlayer())) {
            event.setCanceled(true);
            event.getPlayer().sendSystemMessage(Component.translatable("chat.cannot_speak").withStyle(ChatFormatting.RED));
        }
    }

    @SubscribeEvent
    public static void onTimeoutCheck(LivingEvent.LivingTickEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!LoginManager.isAuthenticated(player)) {
                int timeout = Config.SERVER.loginTimeout.get();
                if (timeout > 0) {
                    long joinTime = LoginManager.getJoinTime(player);
                    if (System.currentTimeMillis() - joinTime > timeout * 1000L) {
                        player.connection.disconnect(Component.translatable("auth.message.login_timeout"));
                    }
                }
            }
        }
    }
}