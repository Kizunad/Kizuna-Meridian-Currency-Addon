package dev.kizuna.meridian.currency;

import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.inventory.MobInventory;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;

public final class MobPurseGameTest {
    private static final BlockPos SPAWN = new BlockPos(1, 1, 1);
    private static final int SKELETON_MAX = 3;
    private static final int WANDERING_MIN = 112;
    private static final int WANDERING_MAX = 235;
    private static final int TRUE_MIN = 1490;
    private static final int TRUE_MAX = 2928;
    private static final int CAPTAIN_BONUS_MIN = 3;
    private static final int CAPTAIN_BONUS_MAX = 6;
    private static final int DROP_RADIUS = 2;
    private static final int PICKUP_COUNT = 7;

    @GameTest
    public void bodySetsPurseDenominations(GameTestHelper test) {
        var pillager = spawn(test, EntityTypes.PILLAGER, "wandering_qi");
        var trader = spawn(test, EntityTypes.WANDERING_TRADER, "true_qi");
        test.assertTrue(VanillaDamageFabric.inspectBody(pillager).isPresent(), "未建立游炁夹具身体");
        test.assertTrue(VanillaDamageFabric.inspectBody(trader).isPresent(), "未建立真炁夹具身体");
        assertBand(test, pillager, WANDERING_MIN, WANDERING_MAX);
        assertBand(test, trader, TRUE_MIN, TRUE_MAX);
        test.assertTrue(count(pack(pillager), CurrencyGameTest.FRAGMENT) > 0, "游炁掠夺者缺碎银");
        test.assertTrue(count(pack(trader), CurrencyGameTest.SYCEE) > 0, "真炁行商缺银锭");
        System.out.println("PURSE_TEST_OK body_sets_purse_denominations");
        test.succeed();
    }

    @GameTest
    public void mortalAndExcludedMobsFollowTable(GameTestHelper test) {
        var skeleton = test.spawn(EntityTypes.SKELETON, SPAWN);
        test.assertTrue(VanillaDamageFabric.inspectBody(skeleton).isEmpty(), "夹具骷髅已有身体");
        assertBand(test, skeleton, 1, SKELETON_MAX);
        var pig = test.spawn(EntityTypes.PIG, SPAWN);
        test.assertTrue(!MobInventories.supports(pig) && !pig.hasAttached(MobPurses.ISSUED), "猪领到了钱囊");
        System.out.println("PURSE_TEST_OK mortal_and_excluded_mobs_follow_table");
        test.succeed();
    }

    @GameTest
    public void patrolCaptainGetsLeaderBonus(GameTestHelper test) {
        var ordinary = spawn(test, EntityTypes.PILLAGER, "mortal");
        long base = value(pack(ordinary));
        var level = test.getLevel();
        var id = ordinary.getUUID();
        ordinary.discard();
        var captain = EntityTypes.PILLAGER.create(level, EntitySpawnReason.PATROL);
        captain.setUUID(id);
        captain.addTag("meridian_humanoid.tier.mortal");
        captain.setPatrolLeader(true /* patrolLeader */);
        captain.setPos(Vec3.atBottomCenterOf(test.absolutePos(SPAWN)));
        test.assertTrue(level.addFreshEntity(captain), "巡逻队长未入场");
        assertBand(test, captain, base + CAPTAIN_BONUS_MIN, base + CAPTAIN_BONUS_MAX);
        System.out.println("PURSE_TEST_OK patrol_captain_gets_leader_bonus");
        test.succeed();
    }

    @GameTest
    public void reloadKeepsPhysicalPurseOnce(GameTestHelper test) {
        var mob = test.spawn(EntityTypes.SKELETON, SPAWN);
        var level = test.getLevel();
        long before = value(pack(mob));
        var returned = restore(level, mob, save(mob));
        long after = value(pack(returned));
        test.assertTrue(before > 0 && before == after, "重载重复发钱：原有 " + before + "，现有 " + after);
        var inventory = MobInventories.inventory(returned);
        for (int slot = 0; slot < MobInventory.SIZE; slot++) {
            inventory.extract(slot, Integer.MAX_VALUE);
        }
        var empty = restore(level, returned, save(returned));
        test.assertTrue(value(pack(empty)) == 0, "背囊取空后又补了钱");
        System.out.println("PURSE_TEST_OK reload_keeps_physical_purse_once");
        test.succeed();
    }

    @GameTest
    public void dimensionTransferKeepsPurse(GameTestHelper test) {
        var source = test.spawn(EntityTypes.SKELETON, SPAWN);
        var initial = value(pack(source));
        var target = test.getLevel().getServer().getLevel(Level.NETHER);
        var position = source.blockPosition();
        target.getChunkAt(position);
        var transition = new TeleportTransition(target, Vec3.atCenterOf(position), Vec3.ZERO,
                source.getYRot(), source.getXRot(), Set.of(), TeleportTransition.PLACE_PORTAL_TICKET);
        var moved = (Mob) source.teleport(transition);
        test.assertTrue(moved != null && moved.level() == target, "跨维传送未完成");
        test.assertTrue(initial > 0 && initial == value(pack(moved)), "跨维复制时钱数变化");
        moved.discard();
        System.out.println("PURSE_TEST_OK dimension_transfer_keeps_purse");
        test.succeed();
    }

    @GameTest
    public void oldEntityReceivesPurseOnce(GameTestHelper test) {
        var level = test.getLevel();
        var original = EntityTypes.SKELETON.create(level, EntitySpawnReason.LOAD);
        original.setPos(Vec3.atBottomCenterOf(test.absolutePos(SPAWN)));
        var legacy = save(original);
        legacy.remove("meridian_mob_inventory:inventory");
        var loaded = restore(level, original, legacy);
        long first = value(pack(loaded));
        var returned = restore(level, loaded, save(loaded));
        test.assertTrue(first > 0 && first == value(pack(returned)), "旧存档没有恰好补一次");
        System.out.println("PURSE_TEST_OK old_entity_receives_purse_once");
        test.succeed();
    }

    @GameTest
    public void firstTickAndImmediateDeathIssueAfterBirth(GameTestHelper test) {
        var ticking = test.spawn(EntityTypes.SKELETON, SPAWN);
        var dying = test.spawn(EntityTypes.SKELETON, SPAWN);
        test.assertTrue(!ticking.hasAttached(MobPurses.ISSUED) && !dying.hasAttached(MobPurses.ISSUED),
                "生物入场时抢先发了钱");
        dying.die(dying.damageSources().genericKill());
        var area = dying.getBoundingBox().inflate(DROP_RADIUS);
        var ground = test.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                .map(ItemEntity::getItem).toList();
        long dropped = value(ground);
        test.assertTrue(dropped >= 1 && dropped <= SKELETON_MAX, "出生同刻死亡的钱未落地：" + dropped);
        test.succeedWhen(() -> {
            var carried = MobInventories.inventory(ticking).snapshot();
            long money = value(carried);
            test.assertTrue(ticking.hasAttached(MobPurses.ISSUED) && money >= 1 && money <= SKELETON_MAX,
                    "未开包的骷髅首刻没有领到随身钱");
            System.out.println("PURSE_TEST_OK first_tick_and_immediate_death_issue_after_birth");
        });
    }

    @GameTest
    public void deathDropsRemainingPurseOnce(GameTestHelper test) {
        var mob = spawn(test, EntityTypes.PILLAGER, "wandering_qi");
        var level = test.getLevel();
        var pack = MobInventories.open(mob);
        int slot = 0;
        while (slot < MobInventory.SIZE && value(List.of(pack.peek(slot))) == 0) {
            slot++;
        }
        test.assertTrue(slot < MobInventory.SIZE, "出生实物缺失");
        pack.extract(slot, 1);
        long remaining = value(pack.snapshot());
        mob.die(mob.damageSources().genericKill());
        var area = mob.getBoundingBox().inflate(DROP_RADIUS);
        var state = MobInventories.state(mob);
        state.dropOnDeath(mob, level, Optional.empty());
        var ground = level.getEntitiesOfClass(ItemEntity.class, area).stream().map(ItemEntity::getItem).toList();
        test.assertTrue(remaining > 0 && value(ground) == remaining && value(pack.snapshot()) == 0,
                "死亡掉落和取走后的余额不符");
        System.out.println("PURSE_TEST_OK death_drops_remaining_purse_once");
        test.succeed();
    }

    @GameTest
    public void vanillaPickupAddsOnlyGroundCoins(GameTestHelper test) {
        var mob = spawn(test, EntityTypes.ZOMBIE, "mortal");
        var level = test.getLevel();
        mob.setCanPickUpLoot(true /* canPickUpLoot */);
        var stack = CurrencyGameTest.stack(CurrencyGameTest.CASH, PICKUP_COUNT);
        var item = new ItemEntity(level, mob.getX(), mob.getY(), mob.getZ(), stack);
        item.setDefaultPickUpDelay();
        level.addFreshEntity(item);
        long before = value(pack(mob));
        mob.aiStep();
        test.assertTrue(value(pack(mob)) == before && item.isAlive(), "拾取延迟未生效");
        item.setNoPickUpDelay();
        mob.aiStep();
        test.assertTrue(value(pack(mob)) == before + PICKUP_COUNT && !item.isAlive(), "拾到的钱未转入背囊");
        System.out.println("PURSE_TEST_OK vanilla_pickup_adds_only_ground_coins");
        test.succeed();
    }

    @GameTest
    public void legacyTillMigratesOnlyRemainingStock(GameTestHelper test) {
        var level = test.getLevel();
        var trader = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.LOAD);
        trader.setPos(Vec3.atBottomCenterOf(test.absolutePos(SPAWN)));
        var service = CurrencyService.get(level.getServer());
        var money = CurrencyGameTest.stack(CurrencyGameTest.CASH, PICKUP_COUNT);
        var old = service.openTill(trader.getUUID(), List.of(money, new ItemStack(Items.PAPER)));
        var sink = service.openTill(UUID.randomUUID(), List.of(ItemStack.EMPTY));
        var price = new Price(CurrencyGameTest.COPPER, 1);
        service.settle(UUID.randomUUID(), service.quote(old, sink, price));
        test.assertTrue(level.addFreshEntity(trader), "旧货郎未入场");
        var returned = restore(level, trader, save(trader));
        var contents = pack(returned);
        test.assertTrue(value(contents) == PICKUP_COUNT - 1 && contents.stream().anyMatch(s -> s.is(Items.PAPER)),
                "旧钱匣迁移未使用成交后余款与货物");
        System.out.println("PURSE_TEST_OK legacy_till_migrates_only_remaining_stock");
        test.succeed();
    }

    private static Mob spawn(GameTestHelper test, EntityType<? extends Mob> type, String tier) {
        var level = test.getLevel();
        var mob = type.create(level, EntitySpawnReason.COMMAND);
        mob.addTag("meridian_humanoid.tier." + tier);
        mob.setPos(Vec3.atBottomCenterOf(test.absolutePos(SPAWN)));
        test.assertTrue(level.addFreshEntity(mob), "生物未入场");
        return mob;
    }

    private static void assertBand(GameTestHelper test, Mob mob, long min, long max) {
        long amount = value(pack(mob));
        test.assertTrue(amount >= min && amount <= max, mob.getType() + " 随身 " + amount + " 文，应为 " + min + "–" + max);
    }

    private static List<ItemStack> pack(Mob mob) {
        return MobInventories.open(mob).snapshot();
    }

    private static int count(List<ItemStack> stacks, net.minecraft.resources.Identifier id) {
        return SettlementPlan.count(stacks, id);
    }

    private static long value(List<ItemStack> stacks) {
        long total = 0;
        for (var coin : CurrencyMod.catalog().denominations()) {
            total += coin.value() * count(stacks, coin.item());
        }
        return total;
    }

    private static CompoundTag save(Mob mob) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, mob.registryAccess());
        mob.saveWithoutId(output);
        return output.buildResult();
    }

    private static Mob restore(ServerLevel level, Mob previous, CompoundTag data) {
        var type = previous.getType();
        previous.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        var mob = (Mob) type.create(level, EntitySpawnReason.LOAD);
        mob.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
        if (!level.addFreshEntity(mob)) {
            throw new AssertionError("重载实体未进入世界");
        }
        return mob;
    }
}
