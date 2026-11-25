package dev.kizuna.meridian.currency.client;

import dev.kizuna.meridian.currency.MerchantOffers.Page;
import dev.kizuna.meridian.currency.MerchantService.Selection;
import dev.kizuna.meridian.currency.TradeWire;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

final class TradeSession {
    final UUID id;
    final Component merchant;
    final Component greeting;
    private final Consumer<CustomPacketPayload> send;
    TradeWire.Stock stock;
    Page page = Page.BUY;
    int row;
    int lots = 1;
    private int sequence;
    TradeWire.Bill bill;
    Component notice = Component.empty();

    TradeSession(TradeWire.Open open, Consumer<CustomPacketPayload> send) {
        id = open.session();
        merchant = open.merchant();
        greeting = open.greeting();
        stock = open.stock();
        this.send = send;
    }

    List<TradeWire.Row> rows() {
        return switch (page) {
            case BUY -> stock.buy();
            case SELL -> stock.sell();
        };
    }

    void turn(Page next) {
        page = next;
        select(0);
    }

    void select(int index) {
        row = index;
        lots = 1;
        request();
    }

    void adjust(int delta) {
        var rows = rows();
        if (rows.isEmpty()) {
            return;
        }
        var line = rows.get(row);
        var goods = line.goods();
        int limit = Math.min(line.available(), goods.getMaxStackSize() / goods.getCount());
        int next = lots + delta;
        if (next >= 1 && next <= limit) {
            lots = next;
            request();
        }
    }

    void request() {
        bill = null;
        notice = Component.empty();
        sequence++;
        if (!rows().isEmpty()) {
            var selection = new Selection(stock.version(), page, row, lots, sequence);
            send.accept(new TradeWire.Pick(id, selection));
        }
    }

    void receive(TradeWire.Reply reply) {
        if (!id.equals(reply.session())) {
            return;
        }
        int incoming = reply.sequence();
        if (incoming != TradeWire.INVALIDATED && incoming != sequence) {
            return;
        }
        if (incoming == TradeWire.INVALIDATED || reply.stock().version() != stock.version()) {
            // 失效消息撤销在途报价；按稳定货行 ID 保留选择，换序后不能移到别的货物。
            var oldRows = rows();
            stock = reply.stock();
            var rows = rows();
            int index = 0;
            if (!oldRows.isEmpty()) {
                var selected = oldRows.get(row).id();
                while (index < rows.size() && !rows.get(index).id().equals(selected)) {
                    index++;
                }
            }
            if (index >= rows.size()) {
                row = 0;
                lots = 1;
            } else {
                row = index;
            }
            sequence++;
        } else {
            stock = reply.stock();
            row = Math.clamp(row, 0, Math.max(0, rows().size() - 1));
        }
        notice = reply.notice();
        bill = reply.bill().orElse(null);
    }

    record Payment(String label, List<TradeWire.Coin> coins) {}

    List<Payment> payments() {
        return switch (page) {
            case BUY -> List.of(new Payment("pay", bill.debit()), new Payment("change", bill.change()));
            case SELL -> List.of(new Payment("receive", bill.debit()), new Payment("give_change", bill.change()));
        };
    }

    void confirm() {
        if (bill != null) {
            var ticket = new TradeWire.Accept(id, bill.branch(), sequence);
            bill = null;
            notice = Component.empty();
            send.accept(ticket);
        }
    }
}
