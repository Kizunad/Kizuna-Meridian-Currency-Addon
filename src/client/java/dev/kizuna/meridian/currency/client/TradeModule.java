package dev.kizuna.meridian.currency.client;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.inventoryui.window.UiWindowManager;
import dev.kizuna.meridian.currency.TradeWire;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class TradeModule implements ClientModule {
    static final String FEATURE = "meridian_currency:trade";
    private static final String WINDOW = "meridian_currency:counter";
    private static final int MIN_WIDTH = 218;
    private static final int MIN_HEIGHT = 206;
    private static final int WIDTH = 548;
    private static final int HEIGHT = 252;
    private TradeSession active;
    TradeWindow view;
    private Screen host;
    private UiWindowManager.WindowKey window;

    @Override
    public FrameworkCatalog.Module definition() {
        var size = new UiWindowDefinition(WINDOW, WINDOW, MIN_WIDTH, MIN_HEIGHT, WIDTH, HEIGHT,
                Set.of(UiWindowDefinition.Capability.STATION));
        var counter = new FrameworkCatalog.Window(size, FrameworkCatalog.InstancePolicy.BY_OBJECT);
        var feature = new FrameworkCatalog.Feature(FEATURE, "交易", "工位", "meridian_currency:copper_cash",
                WINDOW, false /* visibleInMenu */);
        return FrameworkCatalog.Module.of("meridian_currency:merchant", Set.of("kiui:core"), counter, feature);
    }

    @Override
    public void register(ClientBindings bindings) {
        bindings.featureAccess(FEATURE, () -> active != null);
        bindings.window(WINDOW, (runtime, state) -> {
            var session = active;
            state.scope().addCleanup(() -> released(session));
            view = new TradeWindow(session, runtime);
            return view;
        });
        ClientPlayNetworking.registerGlobalReceiver(TradeWire.Open.TYPE, (packet, ctx) -> open(packet));
        ClientPlayNetworking.registerGlobalReceiver(TradeWire.Reply.TYPE, (packet, ctx) -> {
            if (active != null) {
                active.receive(packet);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(TradeWire.Close.TYPE, (packet, ctx) -> dismiss(packet.session()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (active != null && client.gui.screen() != host) {
                dismiss(active.id);
            }
        });
    }

    private void open(TradeWire.Open packet) {
        var runtime = InventoryUiClient.runtime();
        if (active != null && active.id.equals(packet.session())) {
            runtime.open(FEATURE, active.id.toString());
            return;
        }
        if (active != null) {
            dismiss(active.id);
        }
        active = new TradeSession(packet, ClientPlayNetworking::send);
        runtime.openWorkspace();
        host = Minecraft.getInstance().gui.screen();
        window = runtime.open(FEATURE, active.id.toString()).key();
        active.select(0);
    }

    private void dismiss(UUID session) {
        if (active != null && active.id.equals(session)) {
            InventoryUiClient.runtime().workspace().windows().close(window);
        }
    }

    private void released(TradeSession session) {
        if (session == active) {
            active = null;
            view = null;
            host = null;
            window = null;
            if (Minecraft.getInstance().getConnection() != null) {
                ClientPlayNetworking.send(new TradeWire.Close(session.id));
            }
        }
    }

    @Override
    public void disconnected(ClientRuntime runtime) {
        active = null;
        view = null;
        host = null;
        window = null;
    }
}
