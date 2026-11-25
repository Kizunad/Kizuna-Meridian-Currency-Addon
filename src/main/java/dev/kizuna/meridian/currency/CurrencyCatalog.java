package dev.kizuna.meridian.currency;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class CurrencyCatalog {
    public record Currency(Identifier id, Identifier group, long rate, long unitsPerMajor) {}
    public record Coin(Identifier item, Currency currency, long units, int stackLimit, long value) {}
    public record Bundle(Identifier item, int count) {
        public Bundle {
            Objects.requireNonNull(item);
            if (count <= 0) {
                throw new IllegalArgumentException("兑换件数必须为正");
            }
        }
    }
    public record Exchange(Bundle input, Bundle output) {}

    private static final int MAX_STACK = 99;
    private final Map<Identifier, Currency> currencies = new LinkedHashMap<>();
    private final Map<Identifier, Coin> coins = new LinkedHashMap<>();
    private final List<Exchange> exchanges = new ArrayList<>();

    public CurrencyCatalog(List<JsonObject> catalogs) {
        // 先合并各附属的币种，再解析引用，加载次序不影响跨目录兑换。
        for (var catalog : catalogs) {
            catalog.getAsJsonArray("currencies").forEach(row -> addCurrency(row.getAsJsonObject()));
        }
        for (var catalog : catalogs) {
            catalog.getAsJsonArray("denominations").forEach(row -> addCoin(row.getAsJsonObject()));
        }
        for (var catalog : catalogs) {
            catalog.getAsJsonArray("exchanges").forEach(row -> addExchange(row.getAsJsonObject()));
        }
    }

    public List<Coin> denominations() {
        return List.copyOf(coins.values());
    }

    public Coin denomination(Identifier id) {
        return required(coins, id);
    }

    public Currency currency(Identifier id) {
        return required(currencies, id);
    }

    public long value(Price price) {
        return Math.multiplyExact(currency(price.currencyId()).rate(), price.units());
    }

    public Component format(Price price) {
        var currency = currency(price.currencyId());
        String key = currency.id().toLanguageKey("currency");
        long units = price.units();
        long major = currency.unitsPerMajor();
        if (major == 1 || units < major) {
            return Component.translatable(key + ".units", units);
        }
        long remainder = units % major;
        if (remainder == 0) {
            return Component.translatable(key + ".major", units / major);
        }
        return Component.translatable(key + ".mixed", units / major, remainder);
    }

    public Exchange exchange(Identifier input, Identifier output) {
        return exchanges.stream()
                .filter(pair -> pair.input().item().equals(input) && pair.output().item().equals(output))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("目录中没有这对拆兑"));
    }

    private void addCurrency(JsonObject row) {
        var id = identifier(row, "id");
        long major = 1;
        if (row.has("units_per_major")) {
            major = positive(row, "units_per_major");
        }
        var currency = new Currency(id, identifier(row, "settlement_group"), positive(row, "rate"), major);
        register(currencies, id, currency);
    }

    private void addCoin(JsonObject row) {
        var item = identifier(row, "item");
        var currency = currency(identifier(row, "currency_id"));
        long units = positive(row, "units");
        int limit = Math.toIntExact(positive(row, "stack_limit"));
        if (limit > MAX_STACK) {
            throw new IllegalArgumentException("面额超过物品堆叠上限 " + item);
        }
        long value = Math.multiplyExact(units, currency.rate());
        register(coins, item, new Coin(item, currency, units, limit, value));
    }

    private void addExchange(JsonObject row) {
        var first = bundle(row, "first");
        var second = bundle(row, "second");
        var left = denomination(first.item());
        var right = denomination(second.item());
        long leftValue = Math.multiplyExact(left.value(), first.count());
        long rightValue = Math.multiplyExact(right.value(), second.count());
        boolean sameGroup = left.currency().group().equals(right.currency().group());
        if (left.equals(right) || !sameGroup || leftValue != rightValue) {
            throw new IllegalArgumentException("拆兑两侧必须同组等值且面额不同");
        }
        var forward = new Exchange(first, second);
        if (exchanges.stream().anyMatch(pair -> pair.input().item().equals(first.item())
                && pair.output().item().equals(second.item()))) {
            throw new IllegalArgumentException("重复拆兑 " + forward);
        }
        exchanges.add(forward);
        exchanges.add(new Exchange(second, first));
    }

    private static Bundle bundle(JsonObject row, String key) {
        return new Bundle(identifier(row, key), Math.toIntExact(positive(row, key + "_count")));
    }

    private static Identifier identifier(JsonObject row, String key) {
        return Identifier.parse(row.get(key).getAsString());
    }

    private static long positive(JsonObject row, String key) {
        var field = row.getAsJsonPrimitive(key);
        if (!field.isNumber()) {
            throw new IllegalArgumentException("需要整数 " + key);
        }
        long value = field.getAsBigDecimal().longValueExact();
        if (value <= 0) {
            throw new IllegalArgumentException("需要正整数 " + key);
        }
        return value;
    }

    private static <T> void register(Map<Identifier, T> entries, Identifier id, T entry) {
        if (entries.putIfAbsent(id, entry) != null) {
            throw new IllegalArgumentException("重复登记 " + id);
        }
    }

    private static <T> T required(Map<Identifier, T> entries, Identifier id) {
        T value = entries.get(id);
        if (value == null) {
            throw new IllegalArgumentException("未知的货币定义 " + id);
        }
        return value;
    }
}
