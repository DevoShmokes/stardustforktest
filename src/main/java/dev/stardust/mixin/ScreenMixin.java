package dev.stardust.mixin;

import java.util.Arrays;
import net.minecraft.text.*;
import dev.stardust.util.LogUtil;
import dev.stardust.modules.AntiToS;
import dev.stardust.modules.ChatSigns;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.gui.Drawable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mutable;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.injection.At;
import dev.stardust.mixin.accessor.StyleAccessor;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.client.gui.AbstractParentElement;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * @author Tas [0xTas] <root@0xTas.dev>
 **/
@Mixin(Screen.class)
public abstract class ScreenMixin extends AbstractParentElement implements Drawable {

    @Shadow
    @Final
    @Mutable
    protected Text title;

    // See AntiToS.java
    @Inject(method = "render", at = @At("HEAD"))
    private void censorScreenTitles(CallbackInfo ci) {
        Modules mods = Modules.get();
        if (mods == null) return;
        AntiToS tos = mods.get(AntiToS.class);
        if (!tos.isActive() || !tos.containsBlacklistedText(this.title.getString())) return;
        MutableText txt = Text.literal(tos.censorText(this.title.getString()));
        this.title = txt.setStyle(this.title.getStyle());
    }

    // See ChatSigns.java
    @Inject(method = "handleTextClick", at = @At("HEAD"), cancellable = true)
    private void handleClickESP(@Nullable Style style, CallbackInfoReturnable<Boolean> cir) {
        // Temporarily disabled until 1.21.8 text event API is remapped here
        return;
    }
}
