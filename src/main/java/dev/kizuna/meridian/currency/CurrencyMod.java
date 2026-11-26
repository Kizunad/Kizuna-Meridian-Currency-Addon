package dev.kizuna.meridian.currency;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

public final class CurrencyMod implements ModInitializer {
    private static final String CATALOG_KEY = "meridian_currency:catalog";
    public static final Identifier TAB = Identifier.parse("meridian_currency:money");
    static final AttachmentType<Long> LAST_PAYMENT = AttachmentRegistry.create(
            Identifier.parse("meridian_currency:last_payment"),
            builder -> builder.persistent(Codec.LONG).copyOnDeath());
    private static CurrencyCatalog catalog;

    public static CurrencyCatalog catalog() {
        return catalog;
    }

    @Override
    public void onInitialize() {
        // 物品注册表冻结前读取所有声明的目录，模型和语言键沿用面额物品的资源位置。
        catalog = readCatalogs();
        TradeCounter.register();
        MobPurses.register();
        var items = catalog.denominations().stream().map(CurrencyMod::registerItem).toList();
        var tab = FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.meridian_currency.money"))
                .icon(() -> new ItemStack(items.getFirst()))
                .displayItems((context, output) -> items.forEach(output::accept))
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB, tab);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CurrencyService.get(server).player(handler.player));
        ServerLifecycleEvents.SERVER_STOPPED.register(CurrencyService::close);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CurrencyService.get(server).merchants().disconnected(handler.player));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> CurrencyService.get(server));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                try {
                    CurrencyService.get(server).merchants().reload(resources);
                } catch (IllegalArgumentException invalid) {
                    org.slf4j.LoggerFactory.getLogger("meridian_currency").error("保留重载前的商人货单", invalid);
                }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> CurrencyService.get(server).merchants().expire());
    }

    private static CurrencyCatalog readCatalogs() {
        var definitions = new ArrayList<JsonObject>();
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            var path = mod.getMetadata().getCustomValue(CATALOG_KEY);
            if (path == null) {
                continue;
            }
            var file = mod.findPath(path.getAsString()).orElseThrow();
            try (var reader = Files.newBufferedReader(file)) {
                definitions.add(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        return new CurrencyCatalog(definitions);
    }

    private static Item registerItem(CurrencyCatalog.Coin coin) {
        var id = coin.item();
        var key = ResourceKey.create(Registries.ITEM, id);
        var lore = new ItemLore(List.of(Component.translatable(id.toLanguageKey("item") + ".description")));
        var properties = new Item.Properties()
                .setId(key)
                .stacksTo(coin.stackLimit())
                .component(DataComponents.LORE, lore);
        return Registry.register(BuiltInRegistries.ITEM, id, new Item(properties));
    }
}
