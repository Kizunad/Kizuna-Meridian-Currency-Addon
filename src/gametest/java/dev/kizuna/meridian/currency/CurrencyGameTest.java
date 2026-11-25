package dev.kizuna.meridian.currency;

import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class CurrencyGameTest {
    static final Identifier COPPER = id("copper");
    static final Identifier SILVER = id("silver");
    static final Identifier CASH = id("copper_cash");
    static final Identifier STRING = id("cash_string");
    static final Identifier FRAGMENT = id("silver_fragment");
    static final Identifier SYCEE = id("silver_sycee");
    static final int INGOT_WEN = 1000;
    static final int FRAGMENT_WEN = 100;
    static final int FRAGMENTS_PER_INGOT = 10;
    static final int LARGE_STACK = 64;
    static final int SMALL_STACK = 16;
    static final int PRICE = 120;
    static final int CHANGE_SILVER = 8;
    static final int CHANGE_COPPER = 80;
    private static final int SECOND_STACK = PRICE - LARGE_STACK;

    @GameTest
    public void copperAndSilverPricesAgree(GameTestHelper test) {
        var catalog = CurrencyMod.catalog();
        test.assertTrue(catalog.value(new Price(SILVER, 1)) == FRAGMENT_WEN, "一钱银没有折一百文");
        test.assertTrue(catalog.value(new Price(SILVER, FRAGMENTS_PER_INGOT)) == INGOT_WEN, "一两折文错误");
        test.assertTrue(catalog.denomination(STRING).value() == INGOT_WEN, "一贯折文错误");
        test.assertTrue(catalog.denomination(SYCEE).value() == INGOT_WEN, "银锭折文错误");
        test.assertTrue(stack(CASH, 1).getMaxStackSize() == LARGE_STACK, "铜钱堆叠错误");
        test.assertTrue(stack(FRAGMENT, 1).getMaxStackSize() == LARGE_STACK, "碎银堆叠错误");
        test.assertTrue(stack(STRING, 1).getMaxStackSize() == SMALL_STACK, "一贯钱堆叠错误");
        test.assertTrue(stack(SYCEE, 1).getMaxStackSize() == SMALL_STACK, "银锭堆叠错误");
        test.succeed();
    }

    @GameTest
    public void peddlerTradesPhysicalBundlesBothWays(GameTestHelper test) {
        var service = service(test);
        var purse = till(service, stack(FRAGMENT, FRAGMENTS_PER_INGOT));
        var counter = till(service, stack(SYCEE, 1), stack(STRING, 1), ItemStack.EMPTY);
        swap(service, purse, counter, FRAGMENT, SYCEE);
        swap(service, purse, counter, SYCEE, STRING);
        test.assertTrue(count(purse, STRING) == 1 && count(counter, FRAGMENT) == FRAGMENTS_PER_INGOT, "拆兑丢失实物");
        swap(service, purse, counter, STRING, SYCEE);
        swap(service, purse, counter, SYCEE, FRAGMENT);
        test.assertTrue(count(purse, FRAGMENT) == FRAGMENTS_PER_INGOT && count(counter, SYCEE) == 1, "反向拆兑错误");
        reject(test, TradeRejected.Reason.GOODS, () -> service.exchange(purse, counter, STRING, SYCEE, 1));
        test.succeed();
    }

    @GameTest
    public void priceSpansSeparateStacks(GameTestHelper test) {
        var service = service(test);
        var payer = till(service, stack(CASH, LARGE_STACK), stack(CASH, SECOND_STACK));
        var payee = till(service, ItemStack.EMPTY, ItemStack.EMPTY);
        var quote = service.quote(payer, payee, new Price(COPPER, PRICE));
        test.assertTrue(quote.money().debit().get(CASH.toString()) == PRICE && quote.money().change().isEmpty(),
                "报价未跨栈合并到同一笔付款");
        service.settle(UUID.randomUUID(), quote);
        test.assertTrue(count(payer, CASH) == 0 && count(payee, CASH) == PRICE, "跨栈付款未守恒");
        test.succeed();
    }

    @GameTest
    public void fullTillAcceptsMatchingCoinComponents(GameTestHelper test) {
        var service = service(test);
        var marked = stack(CASH, 1);
        marked.set(DataComponents.CUSTOM_NAME, Component.literal("铺记"));
        var payer = till(service, marked, stack(CASH, 1));
        var payee = till(service, stack(CASH, 1));
        var quote = service.quote(payer, payee, new Price(COPPER, 1));
        service.settle(UUID.randomUUID(), quote);
        test.assertTrue(ItemStack.matches(payer.contents().getFirst(), marked), "满匣合栈误取了带铺记的铜钱");
        test.assertTrue(count(payee, CASH) == 2, "满匣未能合入相同组件的铜钱");
        test.succeed();
    }

    @GameTest
    public void quoteReturnsSmallestAvailableChange(GameTestHelper test) {
        var service = service(test);
        var payer = till(service, stack(SYCEE, SMALL_STACK), ItemStack.EMPTY);
        var payee = till(service, stack(FRAGMENT, LARGE_STACK), ItemStack.EMPTY);
        var quote = service.quote(payer, payee, new Price(COPPER, FRAGMENT_WEN));
        test.assertTrue(quote.money().debit().get(SYCEE.toString()) == 1, "应当只收一锭");
        test.assertTrue(quote.money().change().get(FRAGMENT.toString()) == FRAGMENTS_PER_INGOT - 1, "多找了碎银");
        test.succeed();
    }

    @GameTest
    public void physicalChangeAndGoodsSettleOnce(GameTestHelper test) {
        var service = service(test);
        var payer = buyer(service);
        var payee = seller(service);
        var quote = sale(service, payer, payee);
        test.assertTrue(quote.money().change().get(CASH.toString()) == CHANGE_COPPER, "找零报价的铜钱错误");
        var operation = UUID.randomUUID();
        var first = service.settle(operation, quote);
        var replay = service.settle(operation, quote);
        test.assertTrue(first.equals(replay) && service.receipt(operation).orElseThrow().equals(first), "回执不可重放");
        verifySale(test, payer, payee);
        var different = service.quote(payer, payee, new Price(COPPER, 1));
        reject(test, TradeRejected.Reason.OPERATION_CONFLICT, () -> service.settle(operation, different));
        test.succeed();
    }

    @GameTest
    public void changeAndSpaceRefusalsPreserveBothInventories(GameTestHelper test) {
        var service = service(test);
        var payer = till(service, stack(SYCEE, 1));
        var noChange = till(service, ItemStack.EMPTY);
        var price = new Price(COPPER, PRICE);
        reject(test, TradeRejected.Reason.CHANGE, () -> service.quote(payer, noChange, price));
        var counter = seller(service);
        reject(test, TradeRejected.Reason.SPACE, () -> service.quote(payer, counter, price));
        var insufficient = till(service, stack(CASH, 1));
        reject(test, TradeRejected.Reason.FUNDS, () -> service.quote(insufficient, counter, price));
        var full = till(service, new ItemStack(Items.BRICK));
        reject(test, TradeRejected.Reason.SPACE, () -> service.quote(insufficient, full, new Price(COPPER, 1)));
        test.assertTrue(count(payer, SYCEE) == 1 && count(noChange, SYCEE) == 0, "拒绝时转移了钱款");
        test.assertTrue(count(counter, CASH) == CHANGE_COPPER && count(counter, FRAGMENT) == CHANGE_SILVER,
                "拒绝时钱匣零钱改变");
        test.succeed();
    }

    @GameTest
    public void competingBuyersCannotSpendSameTillRevision(GameTestHelper test) {
        var service = service(test);
        var level = test.getLevel();
        try (var alice = CurrencyRecoveryGameTest.login(level, UUID.randomUUID(), "CashBuyerA");
             var bob = CurrencyRecoveryGameTest.login(level, UUID.randomUUID(), "CashBuyerB")) {
            var first = fund(service, alice);
            var second = fund(service, bob);
            var payee = seller(service);
            var firstQuote = sale(service, first, payee);
            var secondQuote = sale(service, second, payee);
            service.settle(UUID.randomUUID(), firstQuote);
            var losingId = UUID.randomUUID();
            reject(test, TradeRejected.Reason.STALE, () -> service.settle(losingId, secondQuote));
            test.assertTrue(service.receipt(losingId).isEmpty() && count(second, SYCEE) == 1, "争抢失败也记账或扣了钱");
            verifySale(test, first, payee);
        }
        test.succeed();
    }

    private static CurrencyAccount fund(CurrencyService service, CurrencyRecoveryGameTest.Customer customer) {
        var player = customer.player();
        player.getInventory().clearContent();
        player.getInventory().setItem(0, stack(SYCEE, 1));
        return service.player(player);
    }

    @GameTest
    public void reopenedTillKeepsStockAndReceipt(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var path = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("till-test");
        var service = new CurrencyService(server, path);
        var buyerId = UUID.randomUUID();
        var sellerId = UUID.randomUUID();
        var purse = List.of(stack(SYCEE, 1), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        var buyer = service.openTill(buyerId, purse);
        var seller = service.openTill(sellerId, stock());
        var id = CurrencyService.operationId(buyerId, sellerId, id("purchase"), 0);
        var receipt = service.settle(id, sale(service, buyer, seller));
        var reopened = new CurrencyService(server, path);
        verifySale(test, reopened.openTill(buyerId), reopened.openTill(sellerId));
        test.assertTrue(reopened.receipt(id).orElseThrow().equals(receipt), "钱匣重开丢失回执");
        test.succeed();
    }

    @GameTest
    public void restoredTillContentsStillInvalidateCompetingQuote(GameTestHelper test) {
        var service = service(test);
        var first = till(service, stack(CASH, 1));
        var second = till(service, stack(CASH, 1));
        var counter = till(service, new ItemStack(Items.BRICK));
        var price = new Price(COPPER, 1);
        var brick = new ItemStack(Items.BRICK);
        var old = service.quote(second, counter, price, brick);
        service.settle(UUID.randomUUID(), service.quote(first, counter, price, brick));
        service.settle(UUID.randomUUID(), service.quote(counter, first, price, brick));
        var operation = UUID.randomUUID();
        reject(test, TradeRejected.Reason.STALE, () -> service.settle(operation, old));
        test.assertTrue(service.receipt(operation).isEmpty(), "钱匣实物已经复原，旧版报价仍不能落账");
        test.succeed();
    }

    static void verifySale(GameTestHelper test, CurrencyAccount buyer, CurrencyAccount seller) {
        test.assertTrue(count(buyer, FRAGMENT) == CHANGE_SILVER && count(buyer, CASH) == CHANGE_COPPER,
                "买家实物找零应恰好为八钱碎银和八十文");
        test.assertTrue(count(buyer, SYCEE) == 0 && count(seller, SYCEE) == 1, "银锭未恰好转移一次");
        test.assertTrue(count(seller, FRAGMENT) == 0 && count(seller, CASH) == 0, "钱匣没有实际付出零钱");
        test.assertTrue(bricks(buyer) == 1 && bricks(seller) == 0, "双方交货数量没有守恒");
    }

    private static int bricks(CurrencyAccount account) {
        return account.contents().stream().filter(stack -> stack.is(Items.BRICK)).mapToInt(ItemStack::getCount).sum();
    }

    static CurrencyService.Quote sale(CurrencyService service, CurrencyAccount payer, CurrencyAccount payee) {
        return service.quote(payer, payee, new Price(COPPER, PRICE), new ItemStack(Items.BRICK));
    }

    static CurrencyAccount buyer(CurrencyService service) {
        return till(service, stack(SYCEE, 1), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                ItemStack.EMPTY);
    }

    static CurrencyAccount seller(CurrencyService service) {
        return service.openTill(UUID.randomUUID(), stock());
    }

    static List<ItemStack> stock() {
        return List.of(stack(CASH, LARGE_STACK), stack(CASH, CHANGE_COPPER - LARGE_STACK),
                stack(FRAGMENT, CHANGE_SILVER), new ItemStack(Items.BRICK));
    }

    static void reject(GameTestHelper test, TradeRejected.Reason reason, Runnable work) {
        try {
            work.run();
        } catch (TradeRejected rejected) {
            test.assertTrue(rejected.reason() == reason, "拒绝原因错误 " + rejected.reason());
            return;
        }
        test.fail("应拒绝交易 " + reason);
    }

    static CurrencyAccount till(CurrencyService service, ItemStack... slots) {
        return service.openTill(UUID.randomUUID(), List.of(slots));
    }

    static CurrencyService service(GameTestHelper test) {
        return CurrencyService.get(test.getLevel().getServer());
    }

    static int count(CurrencyAccount account, Identifier id) {
        return SettlementPlan.count(account.contents(), id);
    }

    static ItemStack stack(Identifier id, int count) {
        var item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow();
        return new ItemStack(item, count);
    }

    static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath("meridian_currency", name);
    }

    private static void swap(CurrencyService service, CurrencyAccount purse, CurrencyAccount counter,
                             Identifier from, Identifier to) {
        var quote = service.exchange(purse, counter, from, to, 1);
        service.settle(UUID.randomUUID(), quote);
    }
}
