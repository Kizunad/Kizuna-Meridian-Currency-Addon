package dev.kizuna.meridian.currency.client;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.meridian.currency.TradeCounter;
import dev.kizuna.meridian.currency.TradeWire;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import static dev.kizuna.meridian.currency.client.CounterFixture.*;

public final class TradeWindowTest implements FabricClientGameTest {
    private static final int WIDE = 1280;
    private static final int TALL = 720;
    private static final int SMALL = 854;
    private static final int SHORT = 480;
    private static final int NARROW = 245;
    private static final int SCALE = 2;
    private static final int SETTLE = 4;
    private static final int CLOSE_INSET = 6;
    private static final int NOTCH = 2;
    private static final int BALANCE_LIANG = 2;
    private static final int BALANCE_WEN = 212;
    private static final int TWO_TORCH_PRICE = 6;
    private record Size(String name, int width, int height) {}
    private ClientGameTestContext ctx;
    private TestServerContext server;
    private TradeModule module;
    private Size size;

    @Override
    public void runTest(ClientGameTestContext context) {
        ctx = context;
        try (var world = ctx.worldBuilder().create()) {
            server = world.getServer();
            server.runOnServer(s -> player(s).setGameMode(GameType.SURVIVAL));
            server.runCommand("time set noon");
            server.runCommand("gamerule advance_time false");
            module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                    .filter(TradeModule.class::isInstance).map(TradeModule.class::cast).findFirst().orElseThrow();
            for (var resolution : List.of(new Size("1280", WIDE, TALL), new Size("854", SMALL, SHORT),
                    new Size("narrow", SMALL, SHORT))) {
                size = resolution;
                resize();
                exercise();
            }
            classic();
        }
    }

    private void resize() {
        ctx.getInput().resizeWindow(size.width(), size.height());
        ctx.runOnClient(client -> {
            client.gui.setScreen(null);
            var runtime = InventoryUiClient.runtime();
            runtime.motion(false /* enabled */);
            runtime.uiVersion(UiVersion.MODERN);
            client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            client.options.guiScale().set(SCALE);
            client.resizeGui();
        });
        ctx.waitFor(client -> client.getWindow().getWidth() == size.width()
                && client.getWindow().getHeight() == size.height());
    }

    private void exercise() {
        var fixture = open(Setup.STOCK);
        awaitBill();
        ctx.runOnClient(client -> {
            var stock = state().stock;
            require(stock.wallet().stream().map(TradeWire.Coin::count).toList().equals(List.of(LOOSE, 1, PAID, 1)),
                    "buy_with_change: 四种面额");
            var expected = TradeCounter.label("mixed", BALANCE_LIANG, BALANCE_WEN);
            require(stock.balance().getString().equals(expected.getString()),
                    "buy_with_change: 随身钱合计");
            movement(new Amount("pay", FRAGMENT, PAID), new Amount("change", CASH, CHANGE));
        });
        if (size.width() == WIDE) {
            checkStockLanguage();
        }
        if (size.name().equals("narrow")) {
            shot("list");
        }
        showDetails();
        shot("buy");
        lateQuote();
        click("counter:road", "back");
        awaitBill();
        click("purchase");
        awaitNotice("done");
        server.runOnServer(fixture::checkPurchase);
        pass("buy_with_change");
        sell(fixture);
        closeByTitle();
        server.runOnServer(s -> fixture.actor.discard());
        reject(Setup.FUNDS, "funds");
        reject(Setup.CHANGE, "change_short");
        reject(Setup.SPACE, "space");
        stale();
    }

    private CounterFixture open(Setup setup) {
        var fixture = server.computeOnServer(s -> new CounterFixture(s, setup));
        ctx.waitFor(client -> module.view != null);
        ctx.runOnClient(client -> {
            var manager = InventoryUiClient.runtime().workspace().windows();
            var state = manager.snapshot().stream().filter(win -> win.key().identity().equals(state().id.toString()))
                    .findFirst().orElseThrow();
            if (size.name().equals("narrow")) {
                manager.resize(state.key(), Integer.toString(NARROW), Integer.toString(state.bounds().height()));
            }
        });
        ctx.waitTicks(SETTLE);
        return fixture;
    }

    private void showDetails() {
        if (size.name().equals("narrow")) {
            click("counter:road");
            awaitBillOrNotice();
        }
    }

    private void checkStockLanguage() {
        ctx.runOnClient(client -> {
            var rows = state().stock.buy();
            var chinese = rows.stream().map(TradeWindow::availableText).toList();
            require(chinese.equals(List.of("3 卷", "32 支", "8 个")), "stock_count_language: " + chinese);
            var original = Language.getInstance();
            try {
                var english = ClientLanguage.loadFrom(client.getResourceManager(), List.of("en_us"),
                        false /* defaultRightToLeft */);
                Language.inject(english);
                var counts = rows.stream().map(TradeWindow::availableText).toList();
                require(counts.equals(List.of("3", "32", "8")), "stock_count_language: " + counts);
            } finally {
                Language.inject(original);
            }
        });
        pass("stock_count_language");
    }

    private void lateQuote() {
        click("counter:torch", "back");
        awaitBill();
        int sequence = server.computeOnServer(s -> dev.kizuna.meridian.currency.CurrencyService.get(s)
                .merchants().lastTicket(player(s)).orElseThrow().sequence());
        var late = ctx.computeOnClient(client -> {
            var state = state();
            return new TradeWire.Reply(state.id, sequence, Component.empty(), Optional.of(state.bill), state.stock);
        });
        click("plus");
        awaitBill();
        server.runOnServer(s -> ServerPlayNetworking.send(player(s), late));
        ctx.waitTicks(SETTLE);
        ctx.runOnClient(client -> require(state().lots == NOTCH && state().bill != null
                && state().bill.total().getString().equals(TradeCounter.label("wen", TWO_TORCH_PRICE).getString()),
                "late_quote_keeps_latest: 旧回复覆盖了两份火把"));
        click("minus");
        awaitBill();
        ctx.runOnClient(client -> require(state().lots == 1, "late_quote_keeps_latest: 减量"));
        pass("late_quote_keeps_latest");
    }


    private void sell(CounterFixture fixture) {
        click("sell");
        awaitBill();
        if (size.name().equals("narrow")) {
            click("counter:leather");
            awaitBill();
        }
        ctx.runOnClient(client -> movement(new Amount("receive", FRAGMENT, 1),
                new Amount("give_change", CASH, SALE_CHANGE)));
        shot("sell");
        click("sale");
        awaitNotice("done");
        server.runOnServer(fixture::checkSale);
        pass("sell_with_change");
    }

    private record Amount(String label, String item, int count) {
        TradeSession.Payment payment() {
            var coin = new TradeWire.Coin(Identifier.parse(item), count);
            return new TradeSession.Payment(label, List.of(coin));
        }
    }

    private void movement(Amount debit, Amount change) {
        var movement = state().payments();
        require(movement.equals(List.of(debit.payment(), change.payment())), "trade_payment_direction: " + movement);
    }

    private void reject(Setup setup, String reason) {
        var fixture = open(setup);
        awaitNotice(reason);
        showDetails();
        awaitNotice(reason);
        shot(reason);
        ctx.runOnClient(client -> require(module.view.target("purchase").action() == null,
                "rejected_button_sends_nothing: 拒绝态购买仍有动作 " + reason));
        click("purchase");
        ctx.waitTicks(SETTLE);
        ctx.runOnClient(client -> require(state().bill == null && notice(reason),
                "rejected_button_sends_nothing: 拒绝后发出了确认"));
        closeByTitle();
        server.runOnServer(s -> fixture.actor.discard());
        pass("rejected_button_sends_nothing/" + reason);
    }

    private void stale() {
        var fixture = open(Setup.STOCK);
        awaitBill();
        showDetails();
        server.runOnServer(s -> player(s).getInventory().setItem(0, net.minecraft.world.item.ItemStack.EMPTY));
        awaitNotice("stale");
        shot("stale");
        ctx.runOnClient(client -> require(state().bill == null, "stale_requires_new_quote: 旧报价仍能确认"));
        click("requote");
        awaitBill();
        server.runOnServer(s -> require(fixture.heldScrolls(s) == 0, "stale_requires_new_quote: 重新看价时交了货"));
        var id = ctx.computeOnClient(client -> state().id);
        ctx.runOnClient(client -> client.gui.screen().onClose());
        ctx.waitFor(client -> module.view == null);
        server.waitFor(s -> CounterFixture.closed(s, id));
        server.runOnServer(s -> fixture.actor.discard());
        pass("stale_requires_new_quote");
    }

    private void classic() {
        size = new Size("classic", WIDE, TALL);
        resize();
        ctx.runOnClient(client -> InventoryUiClient.runtime().uiVersion(UiVersion.CLASSIC));
        var fixture = open(Setup.STOCK);
        awaitBill();
        shot("buy");
        server.runOnServer(s -> fixture.actor.discard());
        ctx.waitFor(client -> module.view == null);
        pass("classic_factory_and_actor_close");
    }

    private void closeByTitle() {
        UUID id = ctx.computeOnClient(client -> state().id);
        ctx.runOnClient(client -> {
            var view = module.view;
            press(client, view.x() + view.width() - CLOSE_INSET, view.y() - CLOSE_INSET);
        });
        ctx.waitFor(client -> module.view == null);
        server.waitFor(s -> CounterFixture.closed(s, id));
    }

    private TradeSession state() {
        return module.view.session;
    }

    private void click(String key, String back) {
        if (size.name().equals("narrow")) {
            click(back);
        }
        click(key);
    }

    private void click(String key) {
        ctx.waitTick();
        ctx.runOnClient(client -> {
            var area = module.view.target(key).rect();
            press(client, area.x() + area.width() / NOTCH, area.y() + area.height() / NOTCH);
        });
        ctx.waitTick();
    }

    private static void press(Minecraft client, int x, int y) {
        var event = new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        client.gui.screen().mouseClicked(event, false /* doubleClick */);
        client.gui.screen().mouseReleased(event);
    }

    private void shot(String name) {
        ctx.runOnClient(client -> client.gui.toastManager().clear());
        ctx.waitTick();
        ctx.takeScreenshot(TestScreenshotOptions.of("trade-" + size.name() + "-" + name).disableCounterPrefix());
    }

    private void awaitBill() {
        ctx.waitFor(client -> module.view != null && state().bill != null);

    }

    private void awaitBillOrNotice() {
        ctx.waitFor(client -> state().bill != null || !state().notice.getString().isEmpty());
    }

    private void awaitNotice(String key) {
        ctx.waitFor(client -> module.view != null && notice(key));
    }

    private boolean notice(String key) {
        return state().notice.getString().equals(TradeCounter.label(key).getString());
    }

    private void pass(String test) {
        System.out.println("TRADE_WINDOW_OK " + size.name() + " " + test);
    }
}
