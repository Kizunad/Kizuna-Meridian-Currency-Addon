package dev.kizuna.meridian.currency;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 每种面额独立抽件数，区间两端都可取到。 */
record PurseTable(Map<EntityType<?>, Bands> profiles, Bands leaderBonus) {
    private static final Identifier FILE = Identifier.parse("meridian_currency:mob_purses.json");
    private static final int MAX_COUNT = 1024;

    record Roll(Item item, int min, int max) {
        void append(RandomSource random, List<ItemStack> stacks) {
            int count = random.nextIntBetweenInclusive(min, max);
            int limit = item.getDefaultMaxStackSize();
            for (int left = count; left > 0; left -= limit) {
                stacks.add(new ItemStack(item, Math.min(left, limit)));
            }
        }
    }

    record Bands(List<Roll> mortal, List<Roll> wandering, List<Roll> cultivated) {
        List<Roll> forTier(PurseTier tier) {
            return switch (tier) {
                case MORTAL -> mortal;
                case WANDERING_QI -> wandering;
                case TRUE_QI -> cultivated;
            };
        }

        static Bands read(JsonObject row) {
            return new Bands(rolls(row, "mortal"), rolls(row, "wandering_qi"), rolls(row, "true_qi"));
        }
    }

    static PurseTable load(ResourceManager resources) {
        try (var reader = resources.getResourceOrThrow(FILE).openAsReader()) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            var profiles = new HashMap<EntityType<?>, Bands>();
            for (var entry : GsonHelper.getAsJsonArray(root, "profiles")) {
                var row = entry.getAsJsonObject();
                var bands = Bands.read(row);
                for (var type : GsonHelper.getAsJsonArray(row, "types")) {
                    var id = Identifier.parse(type.getAsString());
                    var entity = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElseThrow();
                    if (profiles.putIfAbsent(entity, bands) != null) {
                        throw new IllegalArgumentException("随身钱财重复登记 " + id);
                    }
                }
            }
            return new PurseTable(Map.copyOf(profiles), Bands.read(root.getAsJsonObject("leader_bonus")));
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("无法读取随身钱财表 " + FILE, invalid);
        }
    }

    private static List<Roll> rolls(JsonObject row, String tier) {
        var rolls = new ArrayList<Roll>();
        for (var entry : GsonHelper.getAsJsonObject(row, tier).entrySet()) {
            var id = Identifier.parse(entry.getKey());
            var coin = CurrencyMod.catalog().denomination(id);
            var item = BuiltInRegistries.ITEM.getOptional(coin.item()).orElseThrow();
            var bounds = entry.getValue().getAsJsonArray();
            if (bounds.size() != 2) {
                throw new IllegalArgumentException("面额缺少件数上下限 " + id);
            }
            int min = bounds.get(0).getAsBigDecimal().intValueExact();
            int max = bounds.get(1).getAsBigDecimal().intValueExact();
            if (min < 0 || max < min || max > MAX_COUNT) {
                throw new IllegalArgumentException("面额件数越界 " + id);
            }
            rolls.add(new Roll(item, min, max));
        }
        return List.copyOf(rolls);
    }

    List<ItemStack> generate(Mob mob) {
        var profile = profiles.get(mob.getType());
        if (profile == null) {
            return List.of();
        }
        var tier = PurseTier.read(mob);
        var rolls = new ArrayList<>(profile.forTier(tier));
        if (mob instanceof PatrollingMonster patrol && patrol.isPatrolLeader()
                || mob.entityTags().contains("meridian_currency.purse_leader")) {
            rolls.addAll(leaderBonus.forTier(tier));
        }
        var id = mob.getUUID();
        var random = RandomSource.create(id.getMostSignificantBits() ^ id.getLeastSignificantBits());
        var stacks = new ArrayList<ItemStack>();
        rolls.forEach(roll -> roll.append(random, stacks));
        return stacks;
    }
}
