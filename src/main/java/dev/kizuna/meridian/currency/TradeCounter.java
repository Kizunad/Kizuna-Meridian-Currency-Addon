package dev.kizuna.meridian.currency;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class TradeCounter {
    private static final Identifier BULLION = Identifier.parse("meridian_currency:bullion");
    private static final Identifier INGOT = Identifier.parse("meridian_currency:silver_sycee");
    private static final int REFRESH_TICKS = 10;
    private static final Map<ServerPlayer, Watching> WATCHERS = new HashMap<>();
    private record Watching(UUID session, MerchantService.View view) {}

    private TradeCounter() {}

    static void register() {
        var incoming = PayloadTypeRegistry.serverboundPlay();
        incoming.register(TradeWire.Pick.TYPE, TradeWire.Pick.CODEC);
        incoming.register(TradeWire.Accept.TYPE, TradeWire.Accept.CODEC);
        incoming.register(TradeWire.Close.TYPE, TradeWire.Close.CODEC);
        var outgoing = PayloadTypeRegistry.clientboundPlay();
        outgoing.register(TradeWire.Open.TYPE, TradeWire.Open.CODEC);
        outgoing.register(TradeWire.Reply.TYPE, TradeWire.Reply.CODEC);
        outgoing.register(TradeWire.Close.TYPE, TradeWire.Close.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TradeWire.Pick.TYPE, (p, ctx) -> pick(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(TradeWire.Accept.TYPE, (p, ctx) -> accept(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(TradeWire.Close.TYPE, (p, ctx) -> close(ctx.player(), p.session()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> WATCHERS.remove(handler.player));
        ServerLifecycleEvents.SERVER_STOPPED.register(server ->
                WATCHERS.keySet().removeIf(player -> player.level().getServer() == server));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % REFRESH_TICKS == 0) {
                for (var player : server.getPlayerList().getPlayers()) {
                    refresh(player);
                }
            }
        });
    }

    /** 内容宿主完成交互准入后打开窗口。摊位身份和初始实物沿用交易服务。 */
    public static void open(ServerPlayer player, Stall stall, Component greeting) {
        var view = merchants(player).open(player, stall);
        var session = view.session();
        WATCHERS.put(player, new Watching(session, view));
        var packet = new TradeWire.Open(session, stall.actor().getDisplayName(), greeting, stock(view));
        ServerPlayNetworking.send(player, packet);
    }

    private static void close(ServerPlayer player, UUID session) {
        var watching = WATCHERS.get(player);
        if (watching != null && watching.session().equals(session)) {
            WATCHERS.remove(player);
            merchants(player).close(player, session);
        }
    }

    private static void pick(ServerPlayer player, TradeWire.Pick pick) {
        var session = pick.session();
        var selection = pick.selection();
        try {
            var quote = merchants(player).select(player, session, selection);
            var movement = quote.money();
            var bill = new TradeWire.Bill(quote.ticket().branch(), price(quote.price()),
                    coins(movement.debit()), coins(movement.change()));
            reply(player, session, selection.sequence(), Component.empty(), Optional.of(bill));
        } catch (TradeRejected rejected) {
            String reason = reason(rejected.reason(), selection.page());
            reply(player, session, selection.sequence(), label(reason), Optional.empty());
        }
    }

    private static void accept(ServerPlayer player, TradeWire.Accept accept) {
        var session = accept.session();
        var ticket = new MerchantService.Ticket(session, accept.branch(), accept.sequence());
        try {
            merchants(player).confirm(player, ticket);
            reply(player, session, accept.sequence(), label("done"), Optional.empty());
        } catch (TradeRejected rejected) {
            reply(player, session, accept.sequence(), label("stale"), Optional.empty());
        }
    }

    private static void reply(ServerPlayer player, UUID session, int sequence, Component notice,
                              Optional<TradeWire.Bill> quote) {
        try {
            var view = merchants(player).view(player, session);
            WATCHERS.put(player, new Watching(session, view));
            ServerPlayNetworking.send(player, new TradeWire.Reply(session, sequence, notice, quote, stock(view)));
        } catch (TradeRejected closed) {
            close(player, session);
            ServerPlayNetworking.send(player, new TradeWire.Close(session));
        }
    }

    private static void refresh(ServerPlayer player) {
        var watching = WATCHERS.get(player);
        if (watching == null) {
            return;
        }
        var session = watching.session();
        try {
            var view = merchants(player).view(player, session);
            var old = watching.view();
            var stock = view.stock();
            var previous = old.stock();
            if (view.version() != old.version() || stock.revision() != previous.revision()
                    || !view.wallet().equals(old.wallet()) || !sameAvailability(stock.sell(), previous.sell())) {
                reply(player, session, TradeWire.INVALIDATED, label("stale"), Optional.empty());
            }
        } catch (TradeRejected closed) {
            close(player, session);
            ServerPlayNetworking.send(player, new TradeWire.Close(session));
        }
    }

    private static boolean sameAvailability(List<MerchantOffers.Row> rows, List<MerchantOffers.Row> previous) {
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).availableLots() != previous.get(index).availableLots()) {
                return false;
            }
        }
        return true;
    }

    private static MerchantService merchants(ServerPlayer player) {
        return CurrencyService.get(player.level().getServer()).merchants();
    }

    private static String reason(TradeRejected.Reason reason, MerchantOffers.Page page) {
        return switch (reason) {
            case FUNDS -> page == MerchantOffers.Page.BUY ? "funds" : "till_funds";
            case CHANGE -> "change_short";
            case SPACE -> "space";
            case GOODS -> "goods";
            case STALE, OPERATION_CONFLICT -> "stale";
        };
    }

    private static TradeWire.Stock stock(MerchantService.View view) {
        var purse = view.wallet();
        var wallet = CurrencyMod.catalog().denominations().stream()
                .map(coin -> new TradeWire.Coin(coin.item(), purse.counts().get(coin.item()))).toList();
        var stock = view.stock();
        long amount = purse.totalsByGroup().getOrDefault(BULLION, 0L);
        return new TradeWire.Stock(view.version(), rows(stock.buy()), rows(stock.sell()), wallet, wen(amount));
    }

    private static List<TradeWire.Row> rows(List<MerchantOffers.Row> source) {
        return source.stream().map(row -> new TradeWire.Row(row.id(), row.goods(), price(row.price()),
                row.availableLots(), row.countFormat())).toList();
    }

    private static List<TradeWire.Coin> coins(Map<String, Integer> counts) {
        return CurrencyMod.catalog().denominations().stream().map(CurrencyCatalog.Coin::item)
                .filter(item -> counts.containsKey(item.toString()))
                .map(item -> new TradeWire.Coin(item, counts.get(item.toString()))).toList();
    }

    private static Component price(Price price) {
        var catalog = CurrencyMod.catalog();
        if (BULLION.equals(catalog.currency(price.currencyId()).group())) {
            return wen(catalog.value(price));
        }
        return catalog.format(price);
    }

    private static Component wen(long value) {
        long unit = CurrencyMod.catalog().denomination(INGOT).value();
        long major = value / unit;
        long minor = value % unit;
        if (major == 0) {
            return label("wen", minor);
        }
        if (minor == 0) {
            return label("liang", major);
        }
        return label("mixed", major, minor);
    }

    public static Component label(String name, Object... arguments) {
        return Component.translatable("trade.meridian_currency." + name, arguments);
    }
}
