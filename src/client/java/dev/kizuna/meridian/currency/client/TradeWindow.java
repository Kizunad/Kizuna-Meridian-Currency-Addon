package dev.kizuna.meridian.currency.client;

import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.component.ItemDrawing;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.meridian.currency.MerchantOffers.Page;
import dev.kizuna.meridian.currency.TradeWire;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;

import static dev.kizuna.meridian.currency.TradeCounter.label;

final class TradeWindow extends BaseUIComponent {
    private static final int FILL = 100;
    private static final int PAD = 6;
    private static final int GAP = 3;
    private static final int LINE = 12;
    private static final int BUTTON = 16;
    private static final int TAB = 32;
    private static final int ICON = 20;
    private static final int ROW = 26;
    private static final int WALLET = 34;
    private static final int WIDE = 374;
    private static final int HALF = 2;
    private static final int NAME_LINES = 2;
    private static final int PRICE_COLUMN_FRACTION = 5;
    private static final int PAYMENT_LINE = LINE + PAD;
    private static final int QUANTITY_CELLS = 3;
    private static final int PRICE_COLOR = 0xFFF1CD98;
    private static final String TEXT = ThemeTokens.TEXT;
    private static final String MUTED = ThemeTokens.MUTED_TEXT;
    private static final String ACCENT = ThemeTokens.ACCENT;

    final TradeSession session;
    private final ClientRuntime runtime;
    private final Font font = Minecraft.getInstance().font;
    private final List<Target> targets = new ArrayList<>();
    private OwoUIGraphics graphics;
    private int first;
    private int visible;
    private Rect listArea;
    private boolean details;

    record Target(String name, Rect rect, Runnable action) {}

    Target target(String name) {
        return targets.stream().filter(target -> name.equals(target.name())).findFirst().orElseThrow();
    }

    TradeWindow(TradeSession session, ClientRuntime runtime) {
        this.session = session;
        this.runtime = runtime;
        sizing(Sizing.fill(FILL), Sizing.fill(FILL));
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        graphics = context;
        // 货单遮住世界中的姓名牌，窗框仍使用工作区主题。
        context.fill(x, y, x + width, y + height, ARGB.opaque(runtime.color(ThemeTokens.PANEL)));
        targets.clear();
        listArea = null;
        int right = x + width - PAD;
        int left = x + PAD;
        int top = y + PAD;
        int bottom = y + height - PAD - WALLET;
        int body = header(left, right, top);
        if (width < WIDE) {
            if (details) {
                detail(left, right, body);
            } else {
                list(left, right, body, bottom);
            }
        } else {
            int middle = x + width / HALF;
            list(left, middle - PAD, body, bottom);
            fill(new Rect(middle, body, 1, bottom - body), ThemeTokens.BORDER);
            detail(middle + PAD, right, body);
        }
        wallet(left, right, bottom + GAP);
        graphics = null;
    }

    private int header(int left, int right, int top) {
        int tabs = right - TAB * HALF - GAP;
        var name = session.merchant.copy().withStyle(net.minecraft.ChatFormatting.BOLD);
        var clipped = font.substrByWidth(name, tabs - left - GAP);
        graphics.text(font, net.minecraft.locale.Language.getInstance().getVisualOrder(clipped),
                left, top + GAP, runtime.color(TEXT), false /* shadow */);
        int greetingX = left + font.width(name) + PAD;
        int firstLine = top + BUTTON + GAP;
        int ruleY;
        String greeting = session.greeting.getString();
        if (width < WIDE || tabs - PAD - greetingX < font.width(greeting)) {
            text(greeting, left, firstLine, right - left, MUTED);
            ruleY = firstLine + LINE;
        } else {
            text(greeting, greetingX, top + GAP, tabs - PAD - greetingX, MUTED);
            ruleY = firstLine;
        }
        tab(Page.BUY, tabs, top);
        tab(Page.SELL, right - TAB, top);
        rule(left, right, ruleY);
        return ruleY + GAP;
    }

    private void tab(Page page, int left, int top) {
        var box = new Rect(left, top, TAB, BUTTON);
        var title = page == Page.BUY ? "buy" : "sell";
        button(box, title, session.page == page ? ACCENT : TEXT, () -> {
            first = 0;
            details = false;
            session.turn(page);
        });
    }

    private void list(int left, int right, int top, int bottom) {
        int priceWidth = Math.max(TAB, (right - left) / PRICE_COLUMN_FRACTION);
        int stockWidth = TAB;
        int priceX = right - priceWidth;
        int stockX = priceX - stockWidth;
        text(label("list").getString(), left, top, stockX - left, TEXT);
        text(label("remaining").getString(), stockX, top, stockWidth, MUTED);
        right(label("price").getString(), priceX, right, top, MUTED);
        int start = top + LINE;
        var rows = session.rows();
        listArea = new Rect(left, start, right - left, Math.max(1, bottom - start));
        visible = Math.max(1, listArea.height() / ROW);
        first = Math.clamp(first, 0, Math.max(0, rows.size() - visible));
        for (int i = first; i < rows.size() && i < first + visible; i++) {
            int lineY = start + (i - first) * ROW;
            var bounds = new Rect(left, lineY, right - left, ROW - 1);
            var row = rows.get(i);
            drawRow(row, bounds, stockX, priceX, i);
        }
        if (rows.isEmpty()) {
            text(label("empty").getString(), left, start + PAD, right - left, MUTED);
        }
        if (rows.size() > visible) {
            int track = listArea.height();
            int thumb = Math.max(GAP, track * visible / rows.size());
            int offset = (track - thumb) * first / (rows.size() - visible);
            fill(new Rect(right - 1, start + offset, 1, thumb), ACCENT);
        }
    }

    private void drawRow(TradeWire.Row row, Rect box, int stockX, int priceX, int index) {
        int left = box.x();
        int top = box.y();
        int right = left + box.width();
        if (index == session.row) {
            fill(box, ThemeTokens.CONTROL_HOVER);
            fill(new Rect(left, top, 1, box.height()), ACCENT);
        }
        var goods = row.goods();
        ItemDrawing.drawStack(graphics, goods, left + GAP, top + GAP, ICON, ICON);
        int nameX = left + GAP + ICON;
        var lines = font.split(goods.getHoverName(), Math.max(1, stockX - GAP - nameX));
        int count = Math.min(NAME_LINES, lines.size());
        int nameY = top + (ROW - count * font.lineHeight) / HALF;
        for (int i = 0; i < count; i++) {
            graphics.text(font, lines.get(i), nameX, nameY + i * font.lineHeight, runtime.color(TEXT),
                    false /* shadow */);
        }
        int center = top + (ROW - font.lineHeight) / HALF;
        text(availableText(row), stockX, center, priceX - stockX, MUTED);
        amount(row.price(), priceX, right - GAP, center);
        rule(left, right, top + box.height());
        targets.add(new Target(row.id().toString(), box, () -> {
            details = true;
            session.select(index);
        }));
    }

    static String availableText(TradeWire.Row row) {
        var goods = row.goods();
        int lots = row.available();
        // 按份交易时，余量与数量键都以整份计。
        if (goods.getCount() > 1) {
            return label("stock.lots", lots).getString();
        }
        var itemId = BuiltInRegistries.ITEM.getKey(goods.getItem());
        String itemFormat = "trade." + itemId.toLanguageKey() + ".count";
        String fallback = Language.getInstance().getOrDefault(row.countFormat(), "%s");
        return Component.translatableWithFallback(itemFormat, fallback, lots).getString();
    }

    private void detail(int left, int right, int top) {
        boolean buying = session.page == Page.BUY;
        String title = buying ? "order_buy" : "order_sell";
        int nameX;
        if (width < WIDE) {
            button(new Rect(left, top, TAB, LINE), "back", ACCENT, () -> details = false);
            nameX = left + TAB + GAP;
        } else {
            String heading = label(title).getString();
            nameX = left + font.width(heading) + PAD;
            text(heading, left, top, right - left, TEXT);
        }
        var rows = session.rows();
        if (!rows.isEmpty()) {
            var selected = rows.get(session.row);
            right(selected.goods().getHoverName().getString(), nameX, right, top, MUTED);
            int content = top + LINE + GAP;
            quantity(left, right, content, selected);
            quote(left, right, content);
        }
    }

    private void quote(int left, int right, int content) {
        var bill = session.bill;
        int totalY = content + BUTTON + GAP;
        int debitY = totalY + PAYMENT_LINE;
        text(label("total").getString(), left, totalY, TAB, MUTED);
        if (bill != null) {
            var total = bill.total().copy().withStyle(ChatFormatting.BOLD).withColor(PRICE_COLOR);
            amount(total, left + TAB, right, totalY);
            int paymentY = debitY;
            for (var payment : session.payments()) {
                payment(left, right, paymentY, payment.label(), payment.coins());
                paymentY += PAYMENT_LINE;
            }
        } else {
            text(session.notice.getString(), left, debitY, right - left, ACCENT);
        }
        int confirmY = debitY + PAYMENT_LINE + LINE + GAP;
        var confirm = new Rect(left, confirmY, right - left, BUTTON);
        if (session.notice.equals(label("stale")) || session.notice.equals(label("done"))) {
            button(confirm, "requote", ACCENT, session::request);
            return;
        }
        String action = session.page == Page.BUY ? "purchase" : "sale";
        Runnable accept = session::confirm;
        button(confirm, action, bill == null ? MUTED : ACCENT, bill == null ? null : accept);
    }

    private void quantity(int left, int right, int top, TradeWire.Row row) {
        var goods = row.goods();
        int limit = Math.min(row.available(), goods.getMaxStackSize() / goods.getCount());
        int minus = right - BUTTON * QUANTITY_CELLS;
        text(label("quantity").getString(), left, top + GAP, minus - left, MUTED);
        button(new Rect(minus, top, BUTTON, BUTTON), "minus", session.lots > 1 ? TEXT : MUTED,
                () -> session.adjust(-1));
        right(Integer.toString(session.lots), minus + BUTTON, right - BUTTON - GAP, top + GAP, TEXT);
        button(new Rect(right - BUTTON, top, BUTTON, BUTTON), "plus", session.lots < limit ? TEXT : MUTED,
                () -> session.adjust(1));
    }

    private void payment(int left, int right, int top, String kind, List<TradeWire.Coin> coins) {
        var label = label(kind).getString();
        text(label, left, top, right - left, MUTED);
        int start = left + font.width(label) + GAP;
        if (coins.isEmpty()) {
            right(label("no_change").getString(), start, right, top, TEXT);
            return;
        }
        int end = right;
        for (var coin : coins.reversed()) {
            var stack = stack(coin);
            String name = stack.getHoverName().getString() + " × " + coin.count();
            if (coins.size() > 1) {
                name = "×" + coin.count();
            }
            int iconX = end - Math.min(font.width(name), Math.max(1, end - start - BUTTON)) - BUTTON;
            ItemDrawing.drawStack(graphics, stack, iconX, top - GAP, BUTTON, BUTTON);
            right(name, iconX + BUTTON, end, top, TEXT);
            end = iconX - GAP;
        }
    }

    private void wallet(int left, int right, int top) {
        rule(left, right, top);
        var stock = session.stock;
        var coins = stock.wallet();
        int start;
        int coinY;
        if (width < WIDE) {
            text(label("wallet").getString(), left, top + GAP, TAB, MUTED);
            right(stock.balance().getString(), left + TAB, right, top + GAP, TEXT);
            start = left;
            coinY = top + PAD + LINE;
        } else {
            coinY = top + PAD;
            text(label("wallet").getString(), left, coinY, right - left, MUTED);
            text(stock.balance().getString(), left, coinY + LINE, right - left, TEXT);
            start = left + Math.max(TAB * HALF, font.width(stock.balance()) + PAD);
        }
        int cell = (right - start) / Math.max(1, coins.size());
        for (int i = 0; i < coins.size(); i++) {
            var coin = coins.get(i);
            var stack = stack(coin);
            int cellX = start + i * cell;
            ItemDrawing.drawStack(graphics, stack, cellX, coinY, ICON, ICON);
            int nameX = cellX + ICON;
            text(stack.getHoverName().getString(), nameX, coinY, cell - ICON, MUTED);
            String unitKey = "trade." + coin.item().toLanguageKey() + ".count";
            var count = Component.translatableWithFallback(unitKey, "× %s", coin.count());
            text(count.getString(), nameX, coinY + font.lineHeight, cell - ICON, TEXT);
        }
    }

    private static ItemStack stack(TradeWire.Coin coin) {
        return new ItemStack(BuiltInRegistries.ITEM.getOptional(coin.item()).orElseThrow());
    }

    private void rule(int left, int right, int top) {
        fill(new Rect(left, top, right - left, 1), ThemeTokens.BORDER);
    }

    private void fill(Rect box, String token) {
        graphics.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), runtime.color(token));
    }

    private void button(Rect box, String key, String color, Runnable action) {
        var token = color.equals(ACCENT) ? ThemeTokens.CONTROL_HOVER : ThemeTokens.CONTROL;
        fill(box, token);
        var name = label(key).getString();
        int left = box.x() + Math.max(0, box.width() - font.width(name)) / HALF;
        text(name, left, box.y() + (box.height() - font.lineHeight) / HALF, box.width(), color);
        targets.add(new Target(key, box, action));
    }

    private void amount(Component amount, int left, int end, int top) {
        var clipped = font.substrByWidth(amount, Math.max(1, end - left));
        var value = Language.getInstance().getVisualOrder(clipped);
        int color = runtime.color(TEXT);
        if (runtime.uiVersion() == dev.kizuna.inventoryui.workspace.UiVersion.MODERN) {
            color = PRICE_COLOR;
        }
        graphics.text(font, value, end - font.width(value), top, color, false /* shadow */);
    }

    private void right(String name, int left, int end, int top, String color) {
        int available = end - left;
        text(name, end - Math.min(font.width(name), available), top, available, color);
    }

    private void text(String name, int left, int top, int available, String color) {
        if (available > 0) {
            graphics.text(font, font.plainSubstrByWidth(name, available), left, top, runtime.color(color),
                    false /* shadow */);
        }
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0) {
            for (var target : targets) {
                if (target.rect().contains(x + click.x(), y + click.y())) {
                    if (target.action() == null) {
                        return false;
                    }
                    target.action().run();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
        if (listArea == null || !listArea.contains(x + mouseX, y + mouseY)) {
            return false;
        }
        int limit = Math.max(0, session.rows().size() - visible);
        first = Math.clamp(first - (int) Math.signum(amount), 0, limit);
        return true;
    }
}
