package dev.kizuna.meridian.currency;

import static dev.kizuna.meridian.currency.MerchantFixture.*;
import static dev.kizuna.meridian.currency.MerchantOffers.Page.BUY;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MerchantRestartGameTest {
    private enum Order { CONFIRMED, UNCONFIRMED }
    private static final int HALT_STATUS = 73;
    private static final int PAPER_LOT = 2;
    private static final Identifier PAPER_ITEM = Identifier.parse("minecraft:paper");

    @GameTest
    public void restartRestoresReceiptAndRefusesUnconfirmedTicket(GameTestHelper test) {
        var mode = System.getProperty("meridian.currency.restart", "reload");
        if (!mode.equals("read")) {
            for (var order : Order.values()) {
                place(test, order);
            }
            var server = test.getLevel().getServer();
            server.getPlayerList().saveAll();
            if (mode.equals("halt")) {
                System.out.println("MERCHANT_HALT " + ProcessHandle.current().pid());
                System.out.flush();
                Runtime.getRuntime().halt(HALT_STATUS);
            }
            CurrencyService.close(server);
        }
        for (var order : Order.values()) {
            verify(test, order);
        }
        test.succeed();
    }

    private static void place(GameTestHelper test, Order order) {
        try (var f = fixture(test, order)) {
            f.carry(cash(1));
            var view = f.open(new ItemStack(Items.PAPER, PAPER_LOT), ItemStack.EMPTY);
            var quote = f.quote(view, BUY, PAPER, 1);
            if (order == Order.CONFIRMED) {
                f.confirm(quote);
            }
            f.player.level().getServer().getPlayerList().saveAll();
        }
    }

    private static void verify(GameTestHelper test, Order order) {
        try (var f = fixture(test, order)) {
            var ticket = f.trades.lastTicket(f.player).orElseThrow();
            var operation = ticket.operation(f.player.getUUID());
            var saved = f.currency.receipt(operation);
            if (order == Order.CONFIRMED) {
                var replay = f.trades.confirm(f.player, ticket);
                test.assertTrue(saved.orElseThrow().equals(replay), "重启未返回原回执");
                test.assertTrue(count(f.purse(), PAPER_ITEM) == PAPER_LOT && count(f.till(), PAPER_ITEM) == 0
                        && count(f.purse(), CurrencyGameTest.CASH) == 0 && count(f.till(), CurrencyGameTest.CASH) == 1,
                        "恢复时重复交钱或交货");
            } else {
                CurrencyGameTest.reject(test, TradeRejected.Reason.STALE, () -> f.trades.confirm(f.player, ticket));
                test.assertTrue(saved.isEmpty() && count(f.purse(), CurrencyGameTest.CASH) == 1
                        && count(f.till(), PAPER_ITEM) == PAPER_LOT, "未成交凭据在重启后落账");
            }
            System.out.println("MERCHANT_RESTORE " + order + " " + operation + " pid=" + ProcessHandle.current().pid());
        }
    }

    private static MerchantFixture fixture(GameTestHelper test, Order order) {
        return new MerchantFixture(test, id(order, "player"), id(order, "till"));
    }

    private static UUID id(Order order, String role) {
        return UUID.nameUUIDFromBytes(("merchant/restart/" + order + "/" + role).getBytes(StandardCharsets.UTF_8));
    }
}
