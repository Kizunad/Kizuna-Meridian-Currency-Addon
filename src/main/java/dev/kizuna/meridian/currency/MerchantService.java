package dev.kizuna.meridian.currency;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;

/** 服务端的货单、会话与选货凭据。每个玩家同时只看一个摊位、持有一份未确认报价。 */
public final class MerchantService {
    public record Selection(long version, MerchantOffers.Page page, int index, int lots, int sequence) {}
    public record Wallet(Map<Identifier, Integer> counts, Map<Identifier, Long> totalsByGroup) {}
    public record Stock(List<MerchantOffers.Row> buy, List<MerchantOffers.Row> sell, long revision) {}
    public record View(UUID session, long version, Stock stock, Wallet wallet) {}
    public record Ticket(UUID session, Identifier branch, int sequence) {
        UUID operation(UUID owner) {
            return CurrencyService.operationId(owner, session, branch, sequence);
        }
    }
    public record Quoted(Ticket ticket, Price price, CurrencyService.Movement money) {}

    private record Pending(Ticket ticket, CurrencyService.Quote quote) {}
    private record Issued(UUID owner, UUID stall, Ticket ticket, UUID operation) {}
    private static final Gson JSON = new Gson();

    private static final class Session {
        final UUID id = UUID.randomUUID();
        final ServerPlayer owner;
        final Stall stall;
        final CurrencyAccount till;
        int sequence = -1;
        Pending pending;

        Session(ServerPlayer owner, Stall stall, CurrencyAccount till) {
            this.owner = owner;
            this.stall = stall;
            this.till = till;
        }
    }

    private final MinecraftServer server;
    private final CurrencyService currency;
    private final Path issued;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private Map<Identifier, MerchantOffers> catalogs;
    private long version;

    MerchantService(MinecraftServer server, CurrencyService currency, Path directory) {
        this.server = server;
        this.currency = currency;
        issued = directory.resolve("merchant-tickets");
        reload(server.getResourceManager());
    }

    void reload(ResourceManager resources) {
        requireThread();
        var next = MerchantOffers.load(server, resources);
        catalogs = next;
        version = Math.incrementExact(version);
        sessions.values().forEach(session -> session.pending = null);
    }

    public View open(ServerPlayer player, Stall stall) {
        requireOnline(player);
        if (!stall.accessible(player) || !catalogs.containsKey(stall.catalog())) {
            throw new TradeRejected(TradeRejected.Reason.STALE);
        }
        var till = currency.merchant(stall.actor());
        var session = new Session(player, stall, till);
        sessions.put(player.getUUID(), session);
        return view(player, session.id);
    }

    public View view(ServerPlayer player, UUID sessionId) {
        var session = active(player, sessionId);
        var catalog = catalogs.get(session.stall.catalog());
        var stock = session.till.contents();
        var purse = currency.player(player).contents();
        var buy = catalog.rows(MerchantOffers.Page.BUY, stock, purse);
        var sell = catalog.rows(MerchantOffers.Page.SELL, stock, purse);
        return new View(sessionId, version, new Stock(buy, sell, session.till.watermark()), wallet(purse));
    }

    private static Wallet wallet(List<ItemStack> purse) {
        var counts = new HashMap<Identifier, Integer>();
        var totals = new HashMap<Identifier, Long>();
        for (var coin : CurrencyMod.catalog().denominations()) {
            int count = SettlementPlan.count(purse, coin.item());
            counts.put(coin.item(), count);
            long amount = Math.multiplyExact(coin.value(), count);
            totals.merge(coin.currency().group(), amount, Math::addExact);
        }
        return new Wallet(Map.copyOf(counts), Map.copyOf(totals));
    }

    public Quoted select(ServerPlayer player, UUID sessionId, Selection selection) {
        var session = active(player, sessionId);
        if (selection.sequence() <= session.sequence) {
            throw new TradeRejected(TradeRejected.Reason.STALE);
        }
        session.sequence = selection.sequence();
        session.pending = null;
        var offer = resolve(session, selection);
        var purse = currency.player(player);
        int lots = selection.lots();
        var page = selection.page();
        if (lots > offer.available(page, session.till.contents(), purse.contents())) {
            throw new TradeRejected(TradeRejected.Reason.GOODS);
        }
        var template = offer.template();
        var goods = template.copyWithCount(lots * template.getCount());
        var total = offer.total(lots);
        var quote = switch (page) {
            case BUY -> currency.quote(purse, session.till, total, goods);
            case SELL -> currency.quote(session.till, purse, total, goods);
        };
        var branch = offer.id().withPrefix(page.name().toLowerCase(java.util.Locale.ROOT) + "/");
        var ticket = new Ticket(session.id, branch, session.sequence);
        var owner = player.getUUID();
        var binding = new Issued(owner, session.stall.id(), ticket, ticket.operation(owner));
        persist(binding);
        session.pending = new Pending(ticket, quote);
        return new Quoted(ticket, total, quote.money());
    }

    private MerchantOffers.Offer resolve(Session session, Selection selection) {
        if (selection.version() != version) {
            throw new TradeRejected(TradeRejected.Reason.STALE);
        }
        var rows = catalogs.get(session.stall.catalog()).page(selection.page());
        int index = selection.index();
        if (index < 0 || index >= rows.size()) {
            throw new TradeRejected(TradeRejected.Reason.GOODS);
        }
        var offer = rows.get(index);
        var goods = offer.template();
        int lots = selection.lots();
        if (lots <= 0 || lots > goods.getMaxStackSize() / goods.getCount()) {
            throw new TradeRejected(TradeRejected.Reason.GOODS);
        }
        return offer;
    }

    public CurrencyService.Receipt confirm(ServerPlayer player, Ticket ticket) {
        requireOnline(player);
        var operation = ticket.operation(player.getUUID());
        // 操作号含经连接验证的玩家身份；断线前已经落盘的交易直接返回原回执。
        var existing = currency.receipt(operation);
        if (existing.isPresent()) {
            return existing.get();
        }
        var session = active(player, ticket.session());
        var pending = session.pending;
        if (pending == null || !pending.ticket().equals(ticket)) {
            throw new TradeRejected(TradeRejected.Reason.STALE);
        }
        return currency.settle(operation, pending.quote());
    }

    private void persist(Issued binding) {
        // 每个玩家只保留最后发出的未决凭据；旧已决凭据可由玩家身份直接定位货币流水。
        try {
            CurrencyJournal.publish(issued.resolve(binding.owner() + ".json"), JSON.toJson(binding));
        } catch (IOException error) {
            throw new UncheckedIOException("无法保存商人报价凭据", error);
        }
    }

    /** 重连后可取回最近一次已发出的凭据，查成交回执或重新选货。 */
    public Optional<Ticket> lastTicket(ServerPlayer player) {
        requireOnline(player);
        var owner = player.getUUID();
        var path = issued.resolve(owner + ".json");
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            var binding = JSON.fromJson(Files.readString(path), Issued.class);
            var ticket = binding.ticket();
            if (!owner.equals(binding.owner()) || !ticket.operation(owner).equals(binding.operation())) {
                throw new IllegalStateException("商人报价凭据与玩家身份不符");
            }
            return Optional.of(ticket);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public void close(ServerPlayer player, UUID sessionId) {
        requireThread();
        sessions.computeIfPresent(player.getUUID(), (id, session) -> {
            if (session.id.equals(sessionId) && session.owner == player) {
                return null;
            }
            return session;
        });
    }

    void disconnected(ServerPlayer player) {
        var session = sessions.get(player.getUUID());
        if (session != null && session.owner == player) {
            close(player, session.id);
        }
    }

    void expire() {
        requireThread();
        sessions.values().removeIf(session -> !session.stall.accessible(session.owner)
                || !catalogs.containsKey(session.stall.catalog()));
    }

    private Session active(ServerPlayer player, UUID sessionId) {
        requireOnline(player);
        var session = sessions.get(player.getUUID());
        if (session == null || session.owner != player || !session.id.equals(sessionId)) {
            throw new TradeRejected(TradeRejected.Reason.STALE);
        }
        if (!session.stall.accessible(player) || !catalogs.containsKey(session.stall.catalog())) {
            sessions.remove(player.getUUID());
            throw new TradeRejected(TradeRejected.Reason.STALE);
        }
        return session;
    }

    private void requireOnline(ServerPlayer player) {
        requireThread();
        if (server.getPlayerList().getPlayer(player.getUUID()) != player) {
            throw new TradeRejected(TradeRejected.Reason.STALE);
        }
    }

    private void requireThread() {
        CurrencyService.requireThread(server);
    }
}
