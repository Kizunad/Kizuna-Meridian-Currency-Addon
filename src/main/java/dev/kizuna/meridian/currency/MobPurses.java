package dev.kizuna.meridian.currency;

import com.mojang.serialization.Codec;
import dev.kizuna.meridian.inventory.MobInventories;
import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.LoggerFactory;

final class MobPurses {
    private static final Identifier PURSE_ID = Identifier.parse("meridian_currency:mob_purse");
    static final AttachmentType<Boolean> ISSUED = AttachmentRegistry.create(PURSE_ID,
            builder -> builder.persistent(Codec.BOOL));
    private static final Map<MinecraftServer, PurseTable> TABLES = new IdentityHashMap<>();

    private MobPurses() { }

    static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                TABLES.put(server, PurseTable.load(server.getResourceManager())));
        MobInventories.onFirstOpen(mob -> {
            var level = (ServerLevel) mob.level();
            var server = level.getServer();
            CurrencyService.get(server).observe(mob, level, TABLES.get(server));
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                try {
                    TABLES.put(server, PurseTable.load(resources));
                } catch (IllegalArgumentException invalid) {
                    LoggerFactory.getLogger("meridian_currency").error("保留重载前的随身钱财表", invalid);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(TABLES::remove);
    }

}
