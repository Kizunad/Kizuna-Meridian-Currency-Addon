package dev.kizuna.meridian.currency;

import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;

public final class MobPurseClientTest implements FabricClientGameTest {
    private static final int STAND_Y = 100;
    private static final double DISTANCE = 3.5;
    private static final int WEST = -4;
    private static final int EAST = 4;
    private static final int YAW = 180;
    private static final int PORTRAIT_PITCH = 9;
    private static final int GROUND_PITCH = 25;
    private static final int SETTLE_TICKS = 26;
    private static final int SYNC_TICKS = 4;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (var world = ctx.worldBuilder().create()) {
            var server = world.getServer();
            server.runOnServer(host -> {
                var player = host.getPlayerList().getPlayers().getFirst();
                host.getPlayerList().op(player.nameAndId());
                player.setGameMode(GameType.CREATIVE);
                player.teleportTo(0, STAND_Y, DISTANCE);
            });
            server.runCommand("fill -9 99 -6 9 99 8 minecraft:smooth_sandstone");
            server.runCommand("fill -9 100 -6 9 105 8 minecraft:air");
            server.runCommand("weather clear");
            server.runCommand("time set noon");
            world.getConnection().waitForChunksRender();
            scene(ctx, server, new Subject(EntityTypes.PILLAGER, "wandering_qi", "游炁掠夺者", WEST, "silver_fragment"));
            scene(ctx, server, new Subject(EntityTypes.WANDERING_TRADER, "true_qi", "真炁行商", EAST, "silver_sycee"));
        }
    }

    private record Subject(EntityType<? extends Mob> type, String tier, String name, int x, String denomination) {}

    private static void scene(ClientGameTestContext ctx, TestServerContext server, Subject subject) {
        var id = server.computeOnServer(host -> spawn(host, subject));
        ctx.waitFor(client -> client.level.getEntity(id) != null);
        ctx.getInput().lookAt(YAW, PORTRAIT_PITCH);
        ctx.runOnClient(client -> {
            client.gui.setScreen(null);
            client.gui.toastManager().clear();
            client.gui.hud.getChat().clearMessages(false /* clearSentHistory */);
        });
        server.runOnServer(host -> {
            var player = host.getPlayerList().getPlayers().getFirst();
            host.getCommands().performPrefixedCommand(player.createCommandSourceStack(),
                    "meridian debug inventory " + id + " list");
        });
        ctx.waitTicks(SYNC_TICKS);
        shot(ctx, subject.tier() + "-inventory");
        server.runOnServer(host -> kill(host, id));
        ctx.getInput().lookAt(YAW, GROUND_PITCH);
        ctx.waitTicks(SETTLE_TICKS);
        shot(ctx, subject.tier() + "-drops");
    }

    private static UUID spawn(MinecraftServer host, Subject subject) {
        var level = host.overworld();
        var mob = subject.type().create(level, EntitySpawnReason.COMMAND);
        mob.addTag("meridian_humanoid.tier." + subject.tier());
        mob.setCustomName(Component.literal(subject.name()));
        mob.setCustomNameVisible(true /* visible */);
        mob.setNoAi(true /* noAi */);
        mob.setPos(subject.x(), STAND_Y, 0);
        level.addFreshEntity(mob);
        if (VanillaDamageFabric.inspectBody(mob).isEmpty()) {
            throw new AssertionError("实拍演员缺少指定境界的身体");
        }
        var coin = Identifier.fromNamespaceAndPath("meridian_currency", subject.denomination());
        if (SettlementPlan.count(MobInventories.open(mob).snapshot(), coin) == 0) {
            throw new AssertionError("实拍演员未生成预期面额 " + coin);
        }
        host.getPlayerList().getPlayers().getFirst().teleportTo(subject.x(), STAND_Y, DISTANCE);
        return mob.getUUID();
    }

    private static void kill(MinecraftServer host, UUID id) {
        var level = host.overworld();
        var mob = (Mob) level.getEntity(id);
        var carried = MobInventories.inventory(mob).snapshot();
        mob.kill(level);
        var ground = level.getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(1))
                .stream().map(ItemEntity::getItem).toList();
        for (var coin : CurrencyMod.catalog().denominations()) {
            var item = coin.item();
            if (SettlementPlan.count(carried, item) != SettlementPlan.count(ground, item)) {
                throw new AssertionError("实拍掉落不守恒 " + item);
            }
        }
    }

    private static void shot(ClientGameTestContext ctx, String name) {
        ctx.takeScreenshot(TestScreenshotOptions.of("mob-purse-" + name).disableCounterPrefix());
    }
}
