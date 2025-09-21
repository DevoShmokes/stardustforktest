package dev.stardust.mixin.xaero;

import xaero.common.HudMod;
import xaero.common.misc.Misc;
import xaero.common.effect.Effects;
import xaero.common.gui.IScreenBase;
import dev.stardust.modules.Meteorites;
import dev.stardust.modules.Minesweeper;
import org.spongepowered.asm.mixin.Mixin;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.client.gui.DrawContext;
import xaero.hud.minimap.module.MinimapSession;
import org.spongepowered.asm.mixin.injection.At;
import xaero.hud.minimap.module.MinimapRenderer;
import dev.stardust.gui.screens.MeteoritesScreen;
import net.minecraft.client.gui.screen.ChatScreen;
import dev.stardust.gui.screens.MinesweeperScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import xaero.hud.render.module.ModuleRenderContext;
import org.spongepowered.asm.mixin.injection.Inject;
import xaero.common.minimap.render.MinimapRendererHelper;
import static meteordevelopment.meteorclient.MeteorClient.mc;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Tas [0xTas] <root@0xTas.dev>
 *     Forces the minimap to remain rendered while playing in-client minigames.
 **/
@Mixin(value = MinimapRenderer.class, remap = false)
public class MinimapRendererMixin {
    @Unique
    private @Nullable Meteorites meteorites = null;

    @Unique
    private @Nullable Minesweeper minesweeper = null;

    @Inject(
        method = "render(Lxaero/hud/minimap/module/MinimapSession;Lxaero/hud/render/module/ModuleRenderContext;Lnet/minecraft/client/gui/DrawContext;F)V",
        at = @At("HEAD"), cancellable = true, remap = true
    )
    private void forceRenderMinimapDuringMinigames(MinimapSession session, ModuleRenderContext c, DrawContext guiGraphics, float partialTicks, CallbackInfo ci) {
        if (mc == null) return;
        if (Misc.hasEffect(mc.player, Effects.NO_MINIMAP) && Misc.hasEffect(mc.player, Effects.NO_MINIMAP_HARMFUL)) return;
        if (meteorites == null || minesweeper == null) {
            Modules mods = Modules.get();
            if (mods == null) return;

            meteorites = mods.get(Meteorites.class);
            minesweeper = mods.get(Minesweeper.class);
            if (meteorites == null || minesweeper == null) return;
        }

        boolean allowedByDefault = (!session.getHideMinimapUnderF3() || !mc.getDebugHud().shouldShowDebugHud())
            && (!session.getHideMinimapUnderScreen() || mc.currentScreen == null || mc.currentScreen instanceof IScreenBase
            || mc.currentScreen instanceof ChatScreen || mc.currentScreen instanceof DeathScreen);

        if (allowedByDefault) return;
        boolean force = mc.currentScreen instanceof MeteoritesScreen && meteorites.renderMap.get();
        if (mc.currentScreen instanceof MinesweeperScreen && minesweeper.renderMap.get()) force = true;

        if (force) {
            boolean invoked = false;
            MinimapRendererHelper.restoreDefaultShaderBlendState();
            Object processor = session.getProcessor();
            Object consumers = HudMod.INSTANCE.getHudRenderer().getCustomVertexConsumers();
            try {
                java.lang.reflect.Method[] methods = processor.getClass().getDeclaredMethods();
                for (java.lang.reflect.Method m : methods) {
                    if (!m.getName().equals("onRender")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    // Try a few common signatures by matching count and assignability
                    Object[] args = null;
                    if (p.length == 10) {
                        // (int,int,int,int,float,int,int,float,DrawContext,consumers) or variant
                        Object[][] candidates = new Object[][]{
                            { c.x, c.y, c.screenWidth, c.screenHeight, c.screenScale, session.getConfiguredWidth(), c.w, partialTicks, guiGraphics, consumers },
                            { c.x, c.y, c.screenWidth, c.screenHeight, c.screenScale, session.getConfiguredWidth(), c.w, partialTicks, consumers, guiGraphics },
                            { guiGraphics, c.x, c.y, c.screenWidth, c.screenHeight, c.screenScale, session.getConfiguredWidth(), c.w, partialTicks, consumers },
                            { guiGraphics, c.x, c.y, c.screenWidth, c.screenHeight, c.screenScale, session.getConfiguredWidth(), c.w, partialTicks, null }
                        };
                        outer: for (Object[] cand : candidates) {
                            boolean ok = true;
                            for (int i = 0; i < p.length; i++) {
                                Object a = cand[i];
                                if (a == null) continue;
                                if (!p[i].isInstance(a) && !(a instanceof Integer && (p[i] == int.class)) && !(a instanceof Float && (p[i] == float.class))) { ok = false; break; }
                            }
                            if (ok) { args = cand; break outer; }
                        }
                    } else if (p.length == 9) {
                        Object[][] candidates = new Object[][]{
                            { c.x, c.y, c.screenWidth, c.screenHeight, c.screenScale, session.getConfiguredWidth(), c.w, partialTicks, guiGraphics },
                            { c.x, c.y, c.screenWidth, c.screenHeight, c.screenScale, session.getConfiguredWidth(), c.w, partialTicks, consumers },
                            { guiGraphics, c.x, c.y, c.screenWidth, c.screenHeight, c.screenScale, session.getConfiguredWidth(), c.w }
                        };
                        outer2: for (Object[] cand : candidates) {
                            boolean ok = true;
                            for (int i = 0; i < p.length; i++) {
                                Object a = cand[i];
                                if (a == null) continue;
                                if (!p[i].isInstance(a) && !(a instanceof Integer && (p[i] == int.class)) && !(a instanceof Float && (p[i] == float.class))) { ok = false; break; }
                            }
                            if (ok) { args = cand; break outer2; }
                        }
                    }
                    if (args != null) {
                        m.setAccessible(true);
                        m.invoke(processor, args);
                        invoked = true;
                        break;
                    }
                }
            } catch (Throwable ignored) { }
            if (invoked) ci.cancel();
            MinimapRendererHelper.restoreDefaultShaderBlendState();
        }
    }
}
