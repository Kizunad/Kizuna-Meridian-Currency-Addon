package dev.kizuna.meridian.currency;

import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.inventory.MobInventory;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;

final class MerchantFixture implements AutoCloseable {
    static final Identifier CATALOG = Identifier.parse("meridian_currency_gametest:counter");
    static final int BOOK = 0;
    static final int PAPER = 1;
    static final int PRICE = CurrencyGameTest.PRICE;
    static final int CHANGE_CASH = CurrencyGameTest.CHANGE_COPPER;
    static final int MAX_CASH = CurrencyGameTest.LARGE_STACK;
    static final int HIDE_PRICE = 35;
    static final int HIDE_TARGET = 3;
    final CurrencyRecoveryGameTest.Customer customer;
    final ServerPlayer player;
    final CurrencyService currency;
    final MerchantService trades;
    final Stall stall;
    private int sequence;

    MerchantFixture(GameTestHelper test, UUID playerId, UUID tillId) {
        var level = test.getLevel();
        customer = CurrencyRecoveryGameTest.login(level, playerId, "Trade" + playerId.toString().substring(0, 8));
        player = customer.player();
        var actor = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.COMMAND);
        actor.setUUID(tillId);
        actor.setPos(Vec3.atBottomCenterOf(test.absolutePos(new BlockPos(1, 1, 1))));
        test.assertTrue(level.addFreshEntity(actor), "交易演员入场失败");
        player.setPos(actor.position());
        currency = CurrencyService.get(level.getServer());
        trades = currency.merchants();
        stall = new Stall(tillId, CATALOG, actor, (viewer, merchant) -> true);
    }

    MerchantFixture(GameTestHelper test) {
        this(test, UUID.randomUUID(), UUID.randomUUID());
    }

    void carry(ItemStack... stacks) {
        var inventory = player.getInventory();
        inventory.clearContent();
        for (int i = 0; i < stacks.length; i++) {
            inventory.setItem(i, stacks[i]);
        }
    }

    MerchantService.View open(ItemStack... slots) {
        if (slots.length > 0) {
            var pack = MobInventories.open(stall.actor());
            for (int i = 0; i < MobInventory.SIZE; i++) {
                pack.extract(i, Integer.MAX_VALUE);
            }
            for (int i = 0; i < slots.length; i++) {
                pack.insertAt(i, slots[i].copy());
            }
        }
        return trades.open(player, stall);
    }

    MerchantService.Quoted quote(MerchantService.View view, MerchantOffers.Page page, int index, int lots) {
        var selection = new MerchantService.Selection(view.version(), page, index, lots, ++sequence);
        return trades.select(player, view.session(), selection);
    }

    CurrencyService.Receipt confirm(MerchantService.Quoted quote) {
        return trades.confirm(player, quote.ticket());
    }

    CurrencyAccount purse() {
        return currency.player(player);
    }

    CurrencyAccount till() {
        return currency.merchant(stall.actor());
    }

    static ItemStack cash(int count) {
        return CurrencyGameTest.stack(CurrencyGameTest.CASH, count);
    }

    static ItemStack silver(int count) {
        return CurrencyGameTest.stack(CurrencyGameTest.FRAGMENT, count);
    }

    static int count(CurrencyAccount account, Identifier item) {
        return CurrencyGameTest.count(account, item);
    }

    static void unchanged(GameTestHelper test, List<ItemStack> old, CurrencyAccount account) {
        var now = account.contents();
        test.assertTrue(old.size() == now.size(), "格数有变");
        for (int i = 0; i < old.size(); i++) {
            test.assertTrue(ItemStack.matches(old.get(i), now.get(i)), "实物有变，格号 " + i);
        }
    }

    @Override
    public void close() {
        stall.actor().discard();
        customer.close();
    }
}
