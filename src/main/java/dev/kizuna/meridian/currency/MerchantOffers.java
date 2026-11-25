package dev.kizuna.meridian.currency;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;

/** 每份物品的组件和件数都来自服务端数据包。公开的物品栈是副本。 */
public final class MerchantOffers {
    public enum Page { BUY, SELL }

    public record Row(Identifier id, ItemStack goods, Price price, int availableLots) {
        public Row {
            goods = goods.copy();
        }

        @Override
        public ItemStack goods() {
            return goods.copy();
        }
    }

    record Offer(Identifier id, ItemStack template, Price price, int target) {
        int available(Page page, List<ItemStack> till, List<ItemStack> purse) {
            int held = count(till);
            int lot = template.getCount();
            if (page == Page.BUY) {
                return held / lot;
            }
            return Math.min(Math.max(0, target - held), count(purse)) / lot;
        }

        private int count(List<ItemStack> contents) {
            return contents.stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, template))
                    .mapToInt(ItemStack::getCount).reduce(0, Math::addExact);
        }

        Price total(int lots) {
            return new Price(price.currencyId(), Math.multiplyExact(price.units(), lots));
        }
    }

    private static final FileToIdConverter FILES = FileToIdConverter.json("merchant_offers");
    private final List<Offer> buy;
    private final List<Offer> sell;

    private MerchantOffers(List<Offer> buy, List<Offer> sell) {
        this.buy = List.copyOf(buy);
        this.sell = List.copyOf(sell);
    }

    static Map<Identifier, MerchantOffers> load(MinecraftServer server, ResourceManager resources) {
        var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var catalogs = new HashMap<Identifier, MerchantOffers>();
        for (var entry : FILES.listMatchingResources(resources).entrySet()) {
            var file = entry.getKey();
            try (var reader = entry.getValue().openAsReader()) {
                var json = GsonHelper.convertToJsonObject(JsonParser.parseReader(reader), "货单");
                catalogs.put(FILES.fileToId(file), parse(ops, json));
            } catch (IOException | RuntimeException invalid) {
                throw new IllegalArgumentException("无法读取商人货单 " + file, invalid);
            }
        }
        return Map.copyOf(catalogs);
    }

    static MerchantOffers parse(DynamicOps<JsonElement> ops, JsonObject json) {
        var ids = new HashSet<Identifier>();
        var buy = new ArrayList<Offer>();
        var sell = new ArrayList<Offer>();
        for (var element : GsonHelper.getAsJsonArray(json, "offers")) {
            var row = element.getAsJsonObject();
            var id = Identifier.parse(GsonHelper.getAsString(row, "id"));
            if (!ids.add(id)) {
                throw new IllegalArgumentException("货行 ID 重复 " + id);
            }
            var goods = ItemStack.CODEC.parse(ops, row.get("goods")).getOrThrow(IllegalArgumentException::new);
            int lot = goods.getCount();
            if (goods.isEmpty() || lot <= 0 || lot > goods.getMaxStackSize()
                    || lot != positiveInt(row.getAsJsonObject("goods"), "count", 1)) {
                throw new IllegalArgumentException("每份件数不合法 " + id);
            }
            var price = Price.CODEC.parse(ops, row.get("price")).getOrThrow(IllegalArgumentException::new);
            // 验证最大一栈总价，未知币种及整数溢出在发布目录前拒绝。
            long value = CurrencyMod.catalog().value(price);
            Math.multiplyExact(value, goods.getMaxStackSize() / lot);
            switch (GsonHelper.getAsString(row, "direction")) {
                case "buy" -> buy.add(new Offer(id, goods, price, 0));
                case "sell" -> sell.add(new Offer(id, goods, price, positiveInt(row, "target", 0)));
                default -> throw new IllegalArgumentException("未知买卖方向 " + id);
            }
        }
        return new MerchantOffers(buy, sell);
    }

    private static int positiveInt(JsonObject row, String key, int defaultValue) {
        if (!row.has(key) && defaultValue > 0) {
            return defaultValue;
        }
        var number = row.getAsJsonPrimitive(key);
        if (number == null || !number.isNumber()) {
            throw new IllegalArgumentException(key + " 须为正整数");
        }
        int value = number.getAsBigDecimal().intValueExact();
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 须为正整数");
        }
        return value;
    }

    List<Offer> page(Page page) {
        return switch (page) {
            case BUY -> buy;
            case SELL -> sell;
        };
    }

    List<Row> rows(Page page, List<ItemStack> stock, List<ItemStack> purse) {
        return page(page).stream().map(offer ->
                new Row(offer.id(), offer.template(), offer.price(), offer.available(page, stock, purse))).toList();
    }
}
