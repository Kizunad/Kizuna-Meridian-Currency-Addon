package dev.kizuna.meridian.currency.client;

import dev.kizuna.meridian.currency.CurrencyAccount;
import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.currency.MerchantService;
import dev.kizuna.meridian.currency.Stall;
import dev.kizuna.meridian.currency.TradeCounter;
import dev.kizuna.meridian.currency.TradeRejected;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.inventory.MobInventory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class CounterFixture {
    enum Setup { STOCK, FUNDS, CHANGE, SPACE }
    static final String CASH = "meridian_currency:copper_cash";
    static final String FRAGMENT = "meridian_currency:silver_fragment";
    static final String SCROLL = "meridian_peddler:road_scroll";
    static final String STRING = "meridian_currency:cash_string";
    static final String INGOT = "meridian_currency:silver_sycee";
    static final Identifier CATALOG = Identifier.parse("meridian_currency_clienttest:counter");
    private static final int ACTOR_DISTANCE = 2;
    static final int LOOSE = 12;
    static final int STACK = 64;
    static final int CHANGE = 80;
    static final int PAID = 2;
    static final int SCROLLS = 3;
    static final int TORCHES = 32;
    static final int BREAD = 8;
    static final int SALE_CHANGE = 65;
    final UUID till = UUID.randomUUID();
    final WanderingTrader actor;

    CounterFixture(MinecraftServer server, Setup setup) {
        var player = player(server);
        var level = player.level();
        actor = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.COMMAND);
        var behind = player.getLookAngle().horizontal().normalize();
        actor.setPos(player.position().subtract(behind.scale(ACTOR_DISTANCE)));
        actor.setNoAi(true /* noAi */);
        actor.setNoGravity(true /* noGravity */);
        actor.setCustomName(Component.literal("行脚货郎"));
        level.addFreshEntity(actor);
        carry(player, setup);
        var stall = new Stall(till, CATALOG, actor, (viewer, merchant) -> true);
        var pack = MobInventories.open(actor);
        for (int i = 0; i < MobInventory.SIZE; i++) {
            pack.extract(i, Integer.MAX_VALUE);
        }
        opening(setup).forEach(pack::insert);
        TradeCounter.open(player, stall, Component.literal("腿法卷、火把和干粮都有，也收皮革。"));
    }

    private static List<ItemStack> opening(Setup setup) {
        var stacks = new ArrayList<ItemStack>();
        for (int i = 0; i < SCROLLS; i++) {
            stacks.add(stack(SCROLL, 1));
        }
        stacks.add(new ItemStack(Items.TORCH, TORCHES));
        stacks.add(new ItemStack(Items.BREAD, BREAD));
        if (setup != Setup.CHANGE) {
            stacks.add(stack(CASH, STACK));
            stacks.add(stack(CASH, CHANGE - STACK));
        }
        stacks.add(ItemStack.EMPTY);
        stacks.add(ItemStack.EMPTY);
        return stacks;
    }

    private static void carry(ServerPlayer player, Setup setup) {
        var inventory = player.getInventory();
        inventory.clearContent();
        if (setup == Setup.SPACE) {
            for (var slot : player.inventoryMenu.slots) {
                if (slot.container == inventory && slot.getContainerSlot() < Inventory.INVENTORY_SIZE
                        && slot.mayPickup(player)) {
                    inventory.setItem(slot.getContainerSlot(), new ItemStack(Items.STONE, STACK));
                }
            }
            inventory.setItem(0, stack(FRAGMENT, PAID + 1));
        } else if (setup == Setup.STOCK) {
            var money = List.of(stack(CASH, LOOSE), stack(STRING, 1), stack(FRAGMENT, PAID),
                    stack(INGOT, 1), new ItemStack(Items.LEATHER));
            for (int i = 0; i < money.size(); i++) {
                inventory.setItem(i, money.get(i));
            }
        } else if (setup == Setup.CHANGE) {
            inventory.setItem(0, stack(FRAGMENT, PAID));
        }
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
    }

    private static ItemStack stack(String item, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.getOptional(Identifier.parse(item)).orElseThrow(), count);
    }

    static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static int count(CurrencyAccount account, String item) {
        var type = BuiltInRegistries.ITEM.getOptional(Identifier.parse(item)).orElseThrow();
        return account.contents().stream().filter(stack -> stack.is(type)).mapToInt(ItemStack::getCount).sum();
    }

    int heldScrolls(MinecraftServer server) {
        return count(CurrencyService.get(server).player(player(server)), SCROLL);
    }

    void checkPurchase(MinecraftServer server) {
        var service = CurrencyService.get(server);
        var purse = service.player(player(server));
        var drawer = service.merchant(actor);
        require(count(purse, CASH) == CHANGE + LOOSE && count(purse, FRAGMENT) == 0 && count(purse, SCROLL) == 1,
                "buy_with_change: 玩家实物");
        require(count(drawer, CASH) == 0 && count(drawer, FRAGMENT) == PAID && count(drawer, SCROLL) == SCROLLS - 1,
                "buy_with_change: 钱匣实物");
    }

    void checkSale(MinecraftServer server) {
        var service = CurrencyService.get(server);
        var purse = service.player(player(server));
        var drawer = service.merchant(actor);
        require(count(purse, CASH) == CHANGE + LOOSE - SALE_CHANGE && count(purse, FRAGMENT) == 1
                && count(purse, "minecraft:leather") == 0, "sell_with_change: 玩家实物");
        require(count(drawer, CASH) == SALE_CHANGE && count(drawer, FRAGMENT) == 1
                && count(drawer, "minecraft:leather") == 1, "sell_with_change: 钱匣实物");
    }

    static boolean closed(MinecraftServer server, UUID session) {
        MerchantService trades = CurrencyService.get(server).merchants();
        try {
            trades.view(player(server), session);
            return false;
        } catch (TradeRejected stale) {
            return stale.reason() == TradeRejected.Reason.STALE;
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
