package dev.kizuna.meridian.currency;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

public final class CurrencyRecoveryGameTest {
    private enum Boundary { DECIDED, DEBITED, DELIVERED }
    private static final int CRASH_STATUS = 73;
    private static final int OLD_SLOT = 7;

    record Customer(ServerPlayer player, RecordingConnection connection) implements AutoCloseable {
        @Override
        public void close() {
            connection.disconnectFromClient();
        }
    }

    @GameTest
    public void recoverMoneyGoodsAndReceiptAtPostingBoundaries(GameTestHelper test) {
        var mode = System.getProperty("meridian.currency.restart", "reload");
        var server = test.getLevel().getServer();
        if (!mode.equals("read")) {
            for (var boundary : Boundary.values()) {
                interruptSale(test, boundary);
            }
            if (mode.equals("halt")) {
                System.out.println("CURRENCY_HALT 三个交割断点已刷盘，进程 " + ProcessHandle.current().pid());
                System.out.flush();
                Runtime.getRuntime().halt(CRASH_STATUS);
            }
            CurrencyService.close(server);
        }
        for (var boundary : Boundary.values()) {
            verifyAfterLogin(test, boundary);
        }
        test.succeed();
    }

    private static void interruptSale(GameTestHelper test, Boundary boundary) {
        var level = test.getLevel();
        var service = CurrencyService.get(level.getServer());
        try (var buyer = login(level, boundary, "buyer"); var seller = login(level, boundary, "seller")) {
            buyer.player().getInventory().clearContent();
            seller.player().getInventory().clearContent();
            buyer.player().getInventory().setItem(0, CurrencyGameTest.stack(CurrencyGameTest.SYCEE, 1));
            var stock = CurrencyGameTest.stock();
            for (int slot = 0; slot < stock.size(); slot++) {
                seller.player().getInventory().setItem(slot, stock.get(slot));
            }
            var payer = service.player(buyer.player());
            var payee = service.player(seller.player());
            var quote = CurrencyGameTest.sale(service, payer, payee);
            var entry = service.commit(key(boundary, "op"), quote);
            if (boundary != Boundary.DECIDED) {
                service.recover(payer);
            }
            if (boundary == Boundary.DELIVERED) {
                service.recover(payee);
            }
            if (boundary == Boundary.DECIDED) {
                // 原版较旧的玩家档里，付出的锭还在移格前的位置。
                var inventory = buyer.player().getInventory();
                inventory.setItem(OLD_SLOT, inventory.removeItemNoUpdate(0));
            }
            test.assertTrue(service.receipt(key(boundary, "op")).orElseThrow().equals(entry.receipt()), "缺少落盘回执");
            verifyCut(test, payer, payee, boundary);
            level.getServer().getPlayerList().saveAll();
        }
    }

    private static void verifyCut(GameTestHelper test, CurrencyAccount payer,
                                  CurrencyAccount payee, Boundary boundary) {
        int buyerIngots = CurrencyGameTest.count(payer, CurrencyGameTest.SYCEE);
        int sellerIngots = CurrencyGameTest.count(payee, CurrencyGameTest.SYCEE);
        int expectedBuyer = boundary == Boundary.DECIDED ? 1 : 0;
        int expectedSeller = boundary == Boundary.DELIVERED ? 1 : 0;
        test.assertTrue(buyerIngots == expectedBuyer && sellerIngots == expectedSeller, "未命中预期交割断点");
        System.out.println("CURRENCY_CUT " + boundary + " buyer_ingots=" + buyerIngots
                + " seller_ingots=" + sellerIngots);
    }

    private static void verifyAfterLogin(GameTestHelper test, Boundary boundary) {
        var level = test.getLevel();
        var service = CurrencyService.get(level.getServer());
        var operation = key(boundary, "op");
        try (var buyer = login(level, boundary, "buyer"); var seller = login(level, boundary, "seller")) {
            var payer = service.player(buyer.player());
            var payee = service.player(seller.player());
            CurrencyGameTest.verifySale(test, payer, payee);
            var receipt = service.receipt(operation).orElseThrow();
            test.assertTrue(payer.watermark() == receipt.revision() && payee.watermark() == receipt.revision(),
                    "存档应用的流水与落盘回执不符");
            System.out.println("CURRENCY_RECOVER " + boundary + " op=" + operation + " revision=" + receipt.revision()
                    + " buyer=880文+砖 seller=1000文 pid=" + ProcessHandle.current().pid());
        }
    }

    private static Customer login(ServerLevel level, Boundary boundary, String role) {
        return login(level, key(boundary, role), role + boundary.ordinal());
    }

    static Customer login(ServerLevel level, UUID id, String name) {
        var profile = new GameProfile(id, name);
        var cookie = CommonListenerCookie.createInitial(profile, false /* transferred */);
        var player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation());
        var connection = new RecordingConnection(List.of());
        TestPlayers.logIn(player, connection);
        return new Customer(player, connection);
    }

    private static UUID key(Boundary boundary, String role) {
        String name = "currency/restart/" + boundary.name() + "/" + role;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
