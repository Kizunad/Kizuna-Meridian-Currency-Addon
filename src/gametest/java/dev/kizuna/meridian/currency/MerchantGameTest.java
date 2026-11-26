package dev.kizuna.meridian.currency;

import static dev.kizuna.meridian.currency.CurrencyGameTest.CASH;
import static dev.kizuna.meridian.currency.CurrencyGameTest.FRAGMENT;
import static dev.kizuna.meridian.currency.CurrencyGameTest.reject;
import static dev.kizuna.meridian.currency.MerchantFixture.*;
import static dev.kizuna.meridian.currency.MerchantOffers.Page.BUY;
import static dev.kizuna.meridian.currency.MerchantOffers.Page.SELL;
import static dev.kizuna.meridian.currency.TradeRejected.Reason.*;

import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MerchantGameTest {
    private static final int FAR = 32;
    private static final int TWO_BOOKS = 2;
    private static final int PRICE_COPPER = 12;
    private static final int SELL_CHANGE = CurrencyGameTest.FRAGMENT_WEN - HIDE_PRICE;
    private static final Identifier BOOK_ITEM = Identifier.parse("minecraft:book");
    private static final Identifier LEATHER = Identifier.parse("minecraft:leather");
    private static final Identifier BULLION = CurrencyGameTest.id("bullion");

    @GameTest
    public void buysWithSplitChangeAndSellsGoodsBothWays(GameTestHelper test) {
        try (var f = new MerchantFixture(test)) {
            f.carry(silver(1), silver(1), cash(PRICE_COPPER), new ItemStack(Items.LEATHER));
            var view = f.open(cash(MAX_CASH), cash(CHANGE_CASH - MAX_CASH), new ItemStack(Items.BOOK), ItemStack.EMPTY);
            var wallet = view.wallet();
            test.assertTrue(wallet.counts().size() == CurrencyMod.catalog().denominations().size()
                    && wallet.totalsByGroup().get(BULLION) == PRICE + CHANGE_CASH + PRICE_COPPER, "四种实物统计不符");
            var buy = f.quote(view, BUY, BOOK, 1);
            test.assertTrue(buy.money().debit().get(FRAGMENT.toString()) == 2
                    && buy.money().change().get(CASH.toString()) == CHANGE_CASH, "跨栈付款或找零不符");
            f.confirm(buy);
            var purse = f.purse();
            var till = f.till();
            test.assertTrue(count(purse, CASH) == CHANGE_CASH + PRICE_COPPER && count(till, CASH) == 0
                    && count(purse, BOOK_ITEM) == 1 && count(till, BOOK_ITEM) == 0, "购货钱货不符");
            var sell = f.quote(view, SELL, 0, 1);
            test.assertTrue(sell.money().debit().get(FRAGMENT.toString()) == 1
                    && sell.money().change().get(CASH.toString()) == SELL_CHANGE, "收购付找方向颠倒");
            f.confirm(sell);
            test.assertTrue(count(purse, FRAGMENT) == 1 && count(till, FRAGMENT) == 1
                    && count(till, CASH) == SELL_CHANGE
                    && count(purse, CASH) == CHANGE_CASH + PRICE_COPPER - SELL_CHANGE
                    && count(purse, LEATHER) == 0 && count(till, LEATHER) == 1, "收购钱货没有守恒");
        }
        test.succeed();
    }

    @GameTest
    public void rejectedOrdersKeepBothInventories(GameTestHelper test) {
        try (var f = new MerchantFixture(test)) {
            f.carry(cash(1));
            var view = f.open(new ItemStack(Items.BOOK), ItemStack.EMPTY);
            var stock = f.till().contents();
            var purse = f.purse().contents();
            reject(test, FUNDS, () -> f.quote(view, BUY, BOOK, 1));
            reject(test, GOODS, () -> f.quote(view, BUY, PAPER, 1));
            reject(test, GOODS, () -> f.quote(view, BUY, BOOK, 0));
            reject(test, GOODS, () -> f.quote(view, BUY, BOOK, Integer.MAX_VALUE));
            reject(test, GOODS, () -> f.quote(view, BUY, -1, 1));
            reject(test, GOODS, () -> f.quote(view, SELL, PAPER, 1));
            unchanged(test, purse, f.purse());
            f.carry(silver(2));
            var large = f.purse().contents();
            reject(test, CHANGE, () -> f.quote(view, BUY, BOOK, 1));
            unchanged(test, large, f.purse());
            unchanged(test, stock, f.till());
            test.assertTrue(f.trades.lastTicket(f.player).isEmpty(), "拒绝的货单仍发了凭据");
        }
        test.succeed();
    }

    @GameTest
    public void noSpaceAndIntakeLimitLeaveGoodsInPlace(GameTestHelper test) {
        try (var f = new MerchantFixture(test)) {
            f.carry(cash(2));
            var full = java.util.Collections.nCopies(dev.kizuna.meridian.inventory.MobInventory.SIZE,
                    new ItemStack(Items.PAPER, MAX_CASH));
            var view = f.open(full.toArray(ItemStack[]::new));
            var till = f.till();
            var stock = till.contents();
            var purse = f.purse().contents();
            reject(test, SPACE, () -> f.quote(view, BUY, PAPER, 1));
            unchanged(test, purse, f.purse());
            unchanged(test, stock, till);
        }
        try (var f = new MerchantFixture(test)) {
            var marked = new ItemStack(Items.LEATHER);
            marked.set(DataComponents.CUSTOM_NAME, Component.literal("留作凭证"));
            f.carry(marked, new ItemStack(Items.LEATHER, HIDE_TARGET));
            var view = f.open(new ItemStack(Items.LEATHER, HIDE_TARGET - 1), cash(HIDE_PRICE), ItemStack.EMPTY);
            test.assertTrue(view.stock().sell().getFirst().availableLots() == 1, "未扣去已收的皮革");
            reject(test, GOODS, () -> f.quote(view, SELL, 0, HIDE_TARGET));
            f.confirm(f.quote(view, SELL, 0, 1));
            test.assertTrue(ItemStack.matches(f.purse().contents().getFirst(), marked), "错收了具名皮革");
            reject(test, GOODS, () -> f.quote(view, SELL, 0, 1));
        }
        test.succeed();
    }

    @GameTest
    public void twoBuyersCompeteForLastBook(GameTestHelper test) {
        try (var a = new MerchantFixture(test); var b = new MerchantFixture(test)) {
            a.carry(cash(MAX_CASH), cash(PRICE - MAX_CASH));
            b.carry(cash(MAX_CASH), cash(PRICE - MAX_CASH));
            var first = a.open(new ItemStack(Items.BOOK), ItemStack.EMPTY);
            b.player.setPos(a.stall.actor().position());
            var second = b.trades.open(b.player, a.stall);
            var win = a.quote(first, BUY, BOOK, 1);
            var lose = b.quote(second, BUY, BOOK, 1);
            var before = b.purse().contents();
            a.confirm(win);
            reject(test, STALE, () -> b.confirm(lose));
            unchanged(test, before, b.purse());
            var till = a.till();
            test.assertTrue(count(till, BOOK_ITEM) == 0 && count(till, CASH) == PRICE
                    && count(a.purse(), BOOK_ITEM) == 1, "最后一卷未恰好交割一次");
            test.assertTrue(b.currency.receipt(lose.ticket().operation(b.player.getUUID())).isEmpty(), "落了输家回执");
            var refreshed = b.trades.view(b.player, second.session()).stock();
            test.assertTrue(refreshed.revision() > first.stock().revision()
                    && refreshed.buy().getFirst().availableLots() == 0, "另一人刷新未见售罄");
        }
        test.succeed();
    }

    @GameTest
    public void changedTillRequiresFreshConfirmation(GameTestHelper test) {
        try (var f = new MerchantFixture(test)) {
            f.carry(cash(MAX_CASH), cash(PRICE - MAX_CASH));
            var view = f.open(new ItemStack(Items.BOOK), cash(1), ItemStack.EMPTY, ItemStack.EMPTY);
            var selected = f.quote(view, BUY, BOOK, 1);
            var till = f.till();
            var other = CurrencyGameTest.till(f.currency, cash(1));
            var penny = new Price(CurrencyGameTest.COPPER, 1);
            f.currency.settle(UUID.randomUUID(), f.currency.quote(other, till, penny));
            var before = f.purse().contents();
            var stock = till.contents();
            reject(test, STALE, () -> f.confirm(selected));
            unchanged(test, before, f.purse());
            unchanged(test, stock, till);
            test.assertTrue(f.currency.receipt(selected.ticket().operation(f.player.getUUID())).isEmpty(), "旧价已落账");
            f.confirm(f.quote(view, BUY, BOOK, 1));
        }
        test.succeed();
    }

    @GameTest
    public void repeatConfirmationReturnsOneReceipt(GameTestHelper test) {
        try (var f = new MerchantFixture(test)) {
            f.carry(silver(TWO_BOOKS), cash(2 * (PRICE - CurrencyGameTest.FRAGMENT_WEN)));
            var view = f.open(new ItemStack(Items.BOOK, TWO_BOOKS), ItemStack.EMPTY, ItemStack.EMPTY);
            var quote = f.quote(view, BUY, BOOK, 1);
            var first = f.confirm(quote);
            var purse = f.purse().contents();
            var stock = f.till().contents();
            var again = f.confirm(quote);
            unchanged(test, purse, f.purse());
            unchanged(test, stock, f.till());
            test.assertTrue(first.equals(again) && f.till().watermark() == first.revision()
                    && count(f.purse(), BOOK_ITEM) == 1, "同一确认没有取回原回执");
            var next = f.confirm(f.quote(view, BUY, BOOK, 1));
            test.assertTrue(!first.operationId().equals(next.operationId())
                    && count(f.purse(), BOOK_ITEM) == TWO_BOOKS, "重新选购没有取得独立订单");
        }
        test.succeed();
    }

    @GameTest
    public void staleSelectionOrClosedStallCannotSettle(GameTestHelper test) {
        try (var f = new MerchantFixture(test); var other = new MerchantFixture(test)) {
            f.carry(cash(1));
            var view = f.open(new ItemStack(Items.PAPER, 2), ItemStack.EMPTY);
            var old = f.quote(view, BUY, PAPER, 1);
            var current = f.quote(view, BUY, PAPER, 1);
            reject(test, STALE, () -> f.confirm(old));
            reject(test, STALE, () -> other.confirm(current));
            var late = new MerchantService.Selection(view.version(), BUY, PAPER, 1, 1);
            reject(test, STALE, () -> f.trades.select(f.player, view.session(), late));
            f.player.setPos(f.stall.actor().position().add(FAR, 0, 0));
            reject(test, STALE, () -> f.confirm(current));
            f.player.setPos(f.stall.actor().position());
            reject(test, STALE, () -> f.confirm(current));
            var reopened = f.open();
            var valid = f.quote(reopened, BUY, PAPER, 1);
            f.trades.close(f.player, reopened.session());
            reject(test, STALE, () -> f.confirm(valid));
        }
        test.succeed();
    }
}
