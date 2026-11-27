package dev.kizuna.meridian.currency;

import static dev.kizuna.meridian.currency.MerchantFixture.*;

import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

public final class ParcelEscrowGameTest {
    private static final int MIDNIGHT = 18_000;
    private static final int DAWN = 24_000;
    private static final int AWAY = 32;
    private static final int COINS = 3;

    @GameTest
    public void escrowDeliversOriginalSilverAfterDawnAndRestart(GameTestHelper test) {
        exercise(test, ParcelEscrow.Contents.SILVER);
    }

    @GameTest
    public void forgedParcelRestoresRetiredMerchantsInventory(GameTestHelper test) {
        exercise(test, ParcelEscrow.Contents.PAPER);
    }

    @GameTest
    public void barterMovesOnlyTheQuotedItems(GameTestHelper test) {
        try (var f = new MerchantFixture(test)) {
            var goods = new ItemStack(Items.BOOK);
            var tender = new ItemStack(Items.EMERALD);
            f.carry(tender.copy());
            f.open(goods.copy());
            var offer = f.currency.barter(f.purse(), f.till(), tender, goods);
            var operation = UUID.randomUUID();
            var receipt = f.currency.settle(operation, offer);
            test.assertTrue(receipt.equals(f.currency.settle(operation, offer)), "物物交换回执不唯一");
            test.assertTrue(f.purse().contents().stream().anyMatch(stack -> ItemStack.matches(stack, goods)),
                    "买方没有拿到同件货");
            test.assertTrue(f.till().contents().stream().anyMatch(stack -> ItemStack.matches(stack, tender)),
                    "摊主没有拿到实物对价");
        }
        test.succeed();
    }

    @GameTest
    public void expiredOrUnderfundedOfferKeepsTheOriginalGoods(GameTestHelper test) {
        long before = test.getLevel().getOverworldClockTime();
        try (var f = new MerchantFixture(test)) {
            test.setTime(MIDNIGHT);
            var seal = new ParcelEscrow.Seal(new ItemStack(Items.BUNDLE),
                    new CurrencyCatalog.Bundle(CurrencyGameTest.FRAGMENT, COINS), ParcelEscrow.Contents.SILVER, DAWN);
            var offer = new ParcelEscrow.Offer(UUID.randomUUID(), new ItemStack(Items.EMERALD), seal);
            var actor = f.stall.actor();
            f.carry(offer.goods().copy());
            f.open(silver(COINS - 1));
            var escrow = f.currency.parcels();
            CurrencyGameTest.reject(test, TradeRejected.Reason.GOODS, () -> escrow.sell(f.player, actor, offer));
            f.open(silver(COINS));
            escrow.preview(f.player, actor, offer);
            test.setTime(DAWN);
            CurrencyGameTest.reject(test, TradeRejected.Reason.STALE, () -> escrow.sell(f.player, actor, offer));
            test.assertTrue(f.currency.receipt(offer.order()).isEmpty(), "无效报价写入了订单");
            test.assertTrue(f.purse().contents().stream().anyMatch(stack -> ItemStack.matches(stack, offer.goods())),
                    "无效报价收走了玩家实物");
        } finally {
            test.setTime(before);
        }
        test.succeed();
    }

    private static void exercise(GameTestHelper test, ParcelEscrow.Contents outcome) {
        var server = test.getLevel().getServer();
        long before = server.overworld().getOverworldClockTime();
        try (var fixture = new MerchantFixture(test)) {
            test.setTime(MIDNIGHT);
            var seal = new ParcelEscrow.Seal(new ItemStack(Items.BUNDLE),
                    new CurrencyCatalog.Bundle(CurrencyGameTest.FRAGMENT, COINS), outcome, DAWN);
            var offer = new ParcelEscrow.Offer(UUID.randomUUID(), new ItemStack(Items.EMERALD), seal);
            var coin = silver(COINS);
            coin.set(DataComponents.CUSTOM_NAME, Component.literal("旧戳"));
            CustomData.update(DataComponents.CUSTOM_DATA, coin, tag -> tag.putInt("stamp_number", 1));
            sellAndLoseParcel(test, fixture, offer, coin);
            CurrencyService.close(server);
            var restored = CurrencyService.get(server);
            var resumed = restored.parcels();
            var player = fixture.player;
            player.setPos(player.position().add(0, 0, AWAY));
            test.setTime(DAWN);
            CurrencyGameTest.reject(test, TradeRejected.Reason.STALE, () -> resumed.open(player, offer.order(), 0));
            boolean carriesReplacement = restored.player(player).contents().stream()
                    .filter(stack -> stack.is(Items.BUNDLE))
                    .map(stack -> stack.get(DataComponents.CUSTOM_DATA).copyTag())
                    .anyMatch(tag -> tag.getStringOr("meridian_currency_seal", "").equals("1"));
            test.assertTrue(carriesReplacement, "重开流水后，玩家随身格里缺少挂失补发的有效封包");
            var receipt = resumed.open(player, offer.order(), 1);
            test.assertTrue(receipt.equals(resumed.open(player, offer.order(), 1)), "同一封签兑了两次");
            verify(test, fixture, offer, coin, restored);
        } finally {
            test.setTime(before);
        }
        test.succeed();
    }

    private static void sellAndLoseParcel(GameTestHelper test, MerchantFixture fixture, ParcelEscrow.Offer offer,
                                          ItemStack coin) {
        var merchant = fixture.stall.actor();
        var player = fixture.player;
        fixture.carry(offer.goods().copy(), cash(1));
        fixture.open(coin);
        var escrow = fixture.currency.parcels();
        escrow.preview(player, merchant, offer);
        test.assertTrue(count(fixture.till(), CurrencyGameTest.FRAGMENT) == COINS, "看价提前扣银");
        var sale = escrow.sell(player, merchant, offer);
        test.assertTrue(sale.equals(escrow.sell(player, merchant, offer)), "成交重试另开了一单");
        test.assertTrue(count(fixture.purse(), CurrencyGameTest.FRAGMENT) == 0, "封银成了当夜可花的钱");
        test.assertTrue(count(fixture.till(), CurrencyGameTest.FRAGMENT) == 0, "封银并未出资");
        CurrencyGameTest.reject(test, TradeRejected.Reason.STALE, () -> escrow.open(player, offer.order(), 0));
        var original = fixture.purse().contents().stream().filter(stack -> stack.is(Items.BUNDLE))
                .findFirst().orElseThrow();
        fixture.carry(cash(1));
        escrow.reissue(player, offer.order(), 1);
        player.getInventory().add(original);
        escrow.depart(merchant, offer.order());
        merchant.discard();
    }

    private static void verify(GameTestHelper test, MerchantFixture fixture, ParcelEscrow.Offer offer,
                               ItemStack coin, CurrencyService service) {
        var returned = EntityTypes.WANDERING_TRADER.create(test.getLevel(), EntitySpawnReason.EVENT);
        returned.setUUID(fixture.stall.id());
        returned.setPos(fixture.player.position());
        test.assertTrue(test.getLevel().addFreshEntity(returned), "原摊主未能重返");
        service.parcels().arrive(returned, UUID.randomUUID());
        var stock = service.merchant(returned).contents();
        var purse = service.player(fixture.player).contents();
        test.assertTrue(stock.stream().anyMatch(stack -> ItemStack.matches(stack, offer.goods())), "售物没有随摊主恢复");
        if (offer.seal().contents() == ParcelEscrow.Contents.SILVER) {
            test.assertTrue(purse.stream().anyMatch(stack -> ItemStack.matches(stack, coin)), "真包没有付原栈");
            test.assertTrue(stock.stream().noneMatch(stack -> ItemStack.matches(stack, coin)), "同一栈银又退还了摊主");
        } else {
            test.assertTrue(stock.stream().anyMatch(stack -> ItemStack.matches(stack, coin)), "假包没有退回原银");
            test.assertTrue(purse.stream().anyMatch(stack -> stack.is(Items.PAPER)), "假包没有交纸");
            test.assertTrue(count(service.player(fixture.player), CurrencyGameTest.FRAGMENT) == 0, "纸钱兑出了银");
        }
        returned.discard();
    }
}
