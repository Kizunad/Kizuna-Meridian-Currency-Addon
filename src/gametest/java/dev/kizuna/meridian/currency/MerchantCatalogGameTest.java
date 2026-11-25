package dev.kizuna.meridian.currency;

import static dev.kizuna.meridian.currency.MerchantFixture.*;
import static dev.kizuna.meridian.currency.MerchantOffers.Page.BUY;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

public final class MerchantCatalogGameTest {
    @GameTest
    public void rejectsOldIndexAfterCatalogReorder(GameTestHelper test) throws IOException {
        try (var f = new MerchantFixture(test)) {
            f.carry(cash(1));
            var view = f.open(new ItemStack(Items.PAPER, 2), new ItemStack(Items.BOOK), ItemStack.EMPTY);
            var quoted = f.quote(view, BUY, PAPER, 1);
            var original = definition(test);
            var rows = original.getAsJsonArray("offers");
            rows.add(rows.remove(0));
            try (var replacement = pack(test, original)) {
                f.trades.reload(replacement);
                var fresh = f.trades.view(f.player, view.session());
                test.assertTrue(fresh.stock().buy().getFirst().goods().is(Items.PAPER), "货单没有换序");
                CurrencyGameTest.reject(test, TradeRejected.Reason.STALE, () -> f.confirm(quoted));
                var inventory = f.till().contents();
                CurrencyGameTest.reject(test, TradeRejected.Reason.STALE, () -> f.quote(view, BUY, BOOK, 1));
                unchanged(test, inventory, f.till());
                f.confirm(f.quote(fresh, BUY, BOOK, 1));
                test.assertTrue(f.purse().contents().stream().anyMatch(stack -> stack.is(Items.PAPER)), "新行没有交纸");
            } finally {
                f.trades.reload(test.getLevel().getServer().getResourceManager());
            }
        }
        test.succeed();
    }

    @GameTest
    public void invalidCatalogKeepsOldQuote(GameTestHelper test) throws IOException {
        try (var f = new MerchantFixture(test)) {
            f.carry(cash(1));
            var view = f.open(new ItemStack(Items.PAPER, 2), ItemStack.EMPTY);
            var quote = f.quote(view, BUY, PAPER, 1);
            var invalid = definition(test);
            var rows = invalid.getAsJsonArray("offers");
            rows.add(rows.get(0).deepCopy());
            try (var replacement = pack(test, invalid)) {
                try {
                    f.trades.reload(replacement);
                    test.fail("重复货行仍发布");
                } catch (IllegalArgumentException expected) {
                    test.assertTrue(expected.getMessage().contains("counter.json"), "没指出出错的货单");
                }
            }
            f.confirm(quote);
        }
        test.succeed();
    }

    @GameTest
    public void checksItemCurrencyDirectionAndOverflow(GameTestHelper test) throws IOException {
        var raw = definition(test).toString();
        List<String> invalid = List.of(raw.replace("minecraft:book", "fixture:missing"),
                raw.replace("meridian_currency:copper", "fixture:missing"), raw.replace("\"buy\"", "\"give\""),
                raw.replace("120", "0"), raw.replace("120", Long.toString(Long.MAX_VALUE)),
                raw.replace("\"target\":3", "\"target\":1.5"), raw.replace("\"count\":2", "\"count\":2.5"));
        var ops = test.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        for (var json : invalid) {
            test.assertTrue(!json.equals(raw), "坏数据没有换中字段");
            try {
                MerchantOffers.parse(ops, JsonParser.parseString(json).getAsJsonObject());
            } catch (IllegalArgumentException | ArithmeticException expected) {
                continue;
            }
            test.fail("接受了非法货单 " + json);
        }
        test.succeed();
    }

    private static JsonObject definition(GameTestHelper test) throws IOException {
        var file = FileToIdConverter.json("merchant_offers").idToFile(CATALOG);
        var resources = test.getLevel().getServer().getResourceManager();
        try (var reader = resources.getResourceOrThrow(file).openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static MultiPackResourceManager pack(GameTestHelper test, JsonObject definition) throws IOException {
        var world = test.getLevel().getServer().getWorldPath(LevelResource.ROOT);
        var root = Files.createTempDirectory(world, "merchant-pack-");
        var file = root.resolve("data/meridian_currency_gametest/merchant_offers/counter.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, definition.toString());
        var info = new PackLocationInfo("trade", Component.literal("trade"), PackSource.DEFAULT, Optional.empty());
        var pack = new PathPackResources(info, root);
        return new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack));
    }
}
