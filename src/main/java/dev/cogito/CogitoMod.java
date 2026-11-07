package dev.cogito;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.cogito.agent.AgentLoop;
import dev.cogito.agent.GameEvents;
import dev.cogito.agent.Reflexes;
import dev.cogito.control.Control;
import dev.cogito.control.VirtualInput;
import dev.cogito.mcp.McpServer;
import dev.cogito.memory.WorldFacts;
import dev.cogito.ui.LlmSettingsScreen;
import dev.cogito.ui.Overlay;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

@Mod(value = CogitoMod.MOD_ID, dist = Dist.CLIENT)
public final class CogitoMod {
    public static final String MOD_ID = "cogito";
    public static final Logger LOG = LogUtils.getLogger();
    public static KeyMapping TAKEOVER_KEY;
    public static KeyMapping SETTINGS_KEY;

    private boolean started;

    public CogitoMod(IEventBus modBus, ModContainer container) {
        // The Config button in the mod list opens the model provider settings.
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new LlmSettingsScreen(parent));
        modBus.addListener(this::registerKeys);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderFrame);
        NeoForge.EVENT_BUS.addListener(this::onRenderGui);
        NeoForge.EVENT_BUS.addListener(this::onRenderScreen);
        NeoForge.EVENT_BUS.addListener(this::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(this::onChatSent);
        NeoForge.EVENT_BUS.addListener(this::onChatReceived);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
    }

    /** Dev runs only: a second test client has no real account, so a world opened to LAN must not ask for one. */
    private void onServerStarted(ServerStartedEvent event) {
        if (Boolean.getBoolean("cogito.devOfflineLan")) {
            event.getServer().setUsesAuthentication(false);
        }
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        TAKEOVER_KEY = new KeyMapping("key.cogito.takeover", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8,
                KeyMapping.Category.MISC);
        event.register(TAKEOVER_KEY);
        SETTINGS_KEY = new KeyMapping("key.cogito.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9,
                KeyMapping.Category.MISC);
        event.register(SETTINGS_KEY);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        if (!started) {
            started = true;
            Config.load();
            McpServer.start();
            AgentLoop.get().start();
        }
        Minecraft mc = Minecraft.getInstance();
        while (SETTINGS_KEY.consumeClick()) {
            if (!Control.isAi() && mc.screen == null) {
                mc.setScreen(new LlmSettingsScreen(null));
            }
        }
        LlmSettingsScreen.tick(mc);
        GameEvents.tick();
        AgentLoop.get().tick();
        Reflexes.tick();
        WorldFacts.tick();
    }

    private void onRenderFrame(RenderFrameEvent.Pre event) {
        VirtualInput.onFrame();
    }

    private void onRenderGui(RenderGuiEvent.Post event) {
        if (Minecraft.getInstance().screen == null) {
            Overlay.draw(event.getGuiGraphics(), false);
        }
    }

    private void onRenderScreen(ScreenEvent.Render.Post event) {
        Overlay.draw(event.getGuiGraphics(), true);
    }

    /** A link would leave the game, and the model settings hold the person's keys, so the AI may open neither. */
    private void onScreenOpening(ScreenEvent.Opening event) {
        if (Control.isAi() && event.getNewScreen() instanceof ConfirmLinkScreen) {
            event.setCanceled(true);
            GameEvents.add("guard", "Blocked an external link while the AI was in control");
        }
        if (Control.isAi() && event.getNewScreen() instanceof LlmSettingsScreen) {
            event.setCanceled(true);
            GameEvents.add("guard", "Blocked the model settings screen while the AI was in control");
        }
    }

    /** "!ai <goal>" typed by the person sets a goal and hands over control; it never reaches the server. */
    private void onChatSent(ClientChatEvent event) {
        String message = event.getMessage().trim();
        if (message.equals("!ai") || message.startsWith("!ai ")) {
            event.setCanceled(true);
            AgentLoop.get().onPersonCommand(message.substring(3).trim());
        }
    }

    private void onChatReceived(ClientChatReceivedEvent event) {
        // The line above the hotbar is not chat: a mod's HUD can refresh it many times a second, which would push every
        // real event out of what the model reads. The status function has it as action_bar.
        if (event instanceof ClientChatReceivedEvent.System system && system.isOverlay()) {
            return;
        }
        String text = event.getMessage().getString();
        GameEvents.addUntrusted("chat", text);
        WorldFacts.heard(event.getSender(), text);
    }
}
