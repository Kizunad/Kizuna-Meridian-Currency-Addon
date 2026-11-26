package dev.kizuna.meridian.currency;

import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class CorpsePurseGameTest {
    private static final Vec3i TEMPLATE_SIZE = new Vec3i(1, 3, 1);
    private static final int HOST_TICKS = 2;
    private static final int MORTAL_MAX = 5;

    @GameTest
    public void eggZombieAndStructureHuskKeepTheirOwnBodyAfterPayout(GameTestHelper test) {
        var origin = test.absolutePos(new BlockPos(1, 1, 1));
        var zombie = hatchZombie(test, origin);
        var husk = placeHusk(test, origin.east().above());
        var corpses = List.of(zombie, husk);
        int eggMoney = openingMoney(test, zombie);
        int structureMoney = openingMoney(test, husk);
        test.runAfterDelay(HOST_TICKS, () -> {
            // 让尸修的真实服务端 tick 先查身体，再核所属 UUID 及背囊实物。
            for (var mob : corpses) {
                var owner = save(mob).getCompound("meridian_humanoid")
                        .flatMap(tag -> tag.read("owner_uuid", UUIDUtil.CODEC));
                test.assertTrue(owner.filter(mob.getUUID()::equals).isPresent(), "尸修每刻更新未绑定本体身份");
                test.assertTrue(VanillaDamageFabric.inspectBody(mob).isPresent(), "健康桥读不到已绑定的尸身");
            }
            test.assertTrue(copper(zombie) == eggMoney && copper(husk) == structureMoney, "建身后钱数改变");
            System.out.println("PURSE_TEST_OK egg_zombie_and_structure_husk_keep_their_own_body_after_payout");
            test.succeed();
        });
    }

    private static Zombie hatchZombie(GameTestHelper test, BlockPos floor) {
        var level = test.getLevel();
        level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        var egg = new ItemStack(Items.ZOMBIE_SPAWN_EGG);
        player.setItemInHand(InteractionHand.MAIN_HAND, egg);
        var hit = new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false /* inside */);
        egg.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        var zombie = only(test, floor.above(), EntityTypes.ZOMBIE);
        zombie.setBaby(false /* baby */);
        return zombie;
    }

    private static Zombie placeHusk(GameTestHelper test, BlockPos origin) {
        var level = test.getLevel();
        var source = EntityTypes.HUSK.create(level, EntitySpawnReason.STRUCTURE);
        source.setPos(Vec3.atCenterOf(origin));
        test.assertTrue(level.addFreshEntity(source), "模板源尸壳未入场");
        var template = new StructureTemplate();
        try {
            test.assertTrue(copper(source) > 0, "模板源尸壳未领钱");
            test.assertTrue(!save(source).contains("meridian_humanoid"), "钱囊抢先给模板源尸壳创建了身体");
            template.fillFromWorld(level, origin, TEMPLATE_SIZE, true /* includeEntities */, List.of());
        } finally {
            source.discard();
        }
        var settings = new StructurePlaceSettings().setFinalizeEntities(true /* finalizeEntities */);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_ALL);
        var copy = only(test, origin, EntityTypes.HUSK);
        test.assertTrue(!copy.getUUID().equals(source.getUUID()), "结构应当产生新实体");
        copy.setBaby(false /* baby */);
        return copy;
    }

    private static Zombie only(GameTestHelper test, BlockPos pos, EntityType<? extends Zombie> type) {
        var mobs = test.getLevel().getEntities(type, new AABB(pos), Entity::isAlive);
        test.assertTrue(mobs.size() == 1, "指定位置应有一只 " + type);
        return mobs.getFirst();
    }

    private static CompoundTag save(Zombie mob) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, mob.registryAccess());
        mob.saveWithoutId(output);
        return output.buildResult();
    }

    private static int copper(Zombie mob) {
        return SettlementPlan.count(MobInventories.open(mob).snapshot(), CurrencyGameTest.CASH);
    }

    private static int openingMoney(GameTestHelper test, Zombie mob) {
        int coins = copper(mob);
        test.assertTrue(mob.hasAttached(MobPurses.ISSUED), "尸修开包未领取随身钱");
        test.assertTrue(coins > 0 && coins <= MORTAL_MAX, "放置来源的钱数越界：" + coins);
        return coins;
    }
}
