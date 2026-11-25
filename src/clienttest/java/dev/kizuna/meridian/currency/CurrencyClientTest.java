package dev.kizuna.meridian.currency;

import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

public final class CurrencyClientTest implements FabricClientGameTest {
    private static final int PRICE = 120;
    private static final int SILVER_CHANGE = 8;
    private static final int FULL_STACK = 64;
    private static final int LOOSE_CASH = 16;
    private static final int ITEM_COUNT = 4;
    private static final int CHANGE_CASH = FULL_STACK + LOOSE_CASH;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getServer().runOnServer(server ->
                    server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.CREATIVE));
            context.waitFor(client -> client.player.getAbilities().instabuild);
            context.runOnClient(CurrencyClientTest::showMoney);
            context.waitTick();
            context.takeScreenshot(TestScreenshotOptions.of("currency-creative").disableCounterPrefix());
            context.runOnClient(client -> client.gui.setScreen(null));

            world.getServer().runOnServer(CurrencyClientTest::purchase);
            context.waitFor(client -> receivedPurchase(client.player));
            context.runOnClient(client -> client.gui.setScreen(new InventoryScreen(client.player)));
            context.waitTick();
            context.takeScreenshot(TestScreenshotOptions.of("currency-change").disableCounterPrefix());
            context.runOnClient(client -> client.gui.setScreen(null));
            world.getServer().runOnServer(server -> server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack(), "dialog show @a meridian_currency:currency_guide"));
            context.waitFor(client -> client.gui.screen() != null && client.gui.screen().getTitle().getString()
                    .equals(Component.translatable("guide.meridian_currency.title").getString()));
            context.takeScreenshot(TestScreenshotOptions.of("currency-guide").disableCounterPrefix());
        }
    }

    private static void showMoney(Minecraft client) {
        var screen = new CreativeModeInventoryScreen(client.player, client.level.enabledFeatures(),
                false /* displayOperatorCreativeTab */);
        client.gui.setScreen(screen);
        var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(CurrencyMod.TAB).orElseThrow();
        var pages = (FabricCreativeModeInventoryScreen) screen;
        pages.switchToPage(pages.getPage(tab));
        pages.setSelectedTab(tab);
        var items = screen.getMenu().slots.subList(0, ITEM_COUNT).stream()
                .map(slot -> BuiltInRegistries.ITEM.getKey(slot.getItem().getItem())).toList();
        var expected = CurrencyMod.catalog().denominations().stream().map(CurrencyCatalog.Coin::item).toList();
        if (!items.equals(expected)) {
            throw new AssertionError("创造栏没有实际显示四种面额 " + items);
        }
    }

    private static void purchase(MinecraftServer server) {
        var player = server.getPlayerList().getPlayers().getFirst();
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().setItem(0, stack("silver_sycee", 1));
        var service = CurrencyService.get(server);
        var purse = service.player(player);
        var stock = List.of(stack("silver_fragment", SILVER_CHANGE), stack("copper_cash", FULL_STACK),
                stack("copper_cash", LOOSE_CASH), new ItemStack(Items.BRICK));
        var counter = service.openTill(UUID.randomUUID(), stock);
        var price = new Price(Identifier.parse("meridian_currency:copper"), PRICE);
        var quote = service.quote(purse, counter, price, new ItemStack(Items.BRICK));
        service.settle(UUID.randomUUID(), quote);
        if (!receivedPurchase(player)) {
            throw new AssertionError("服务端付款后背包实物错误");
        }
    }

    private static boolean receivedPurchase(Player player) {
        var slots = player.getInventory().getNonEquipmentItems();
        int copper = SettlementPlan.count(slots, Identifier.parse("meridian_currency:copper_cash"));
        int silver = SettlementPlan.count(slots, Identifier.parse("meridian_currency:silver_fragment"));
        int ingots = SettlementPlan.count(slots, Identifier.parse("meridian_currency:silver_sycee"));
        return copper == CHANGE_CASH && silver == SILVER_CHANGE && ingots == 0
                && slots.stream().anyMatch(stack -> stack.is(Items.BRICK));
    }

    private static ItemStack stack(String path, int count) {
        var id = Identifier.fromNamespaceAndPath("meridian_currency", path);
        return new ItemStack(BuiltInRegistries.ITEM.getOptional(id).orElseThrow(), count);
    }
}
