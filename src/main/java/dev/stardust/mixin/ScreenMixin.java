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
        if (style == null) return;
        var click = style.getClickEvent();
        if (click == null) return;

        String s = click.toString();
        String marker = "stardust://chatsigns";
        if (s == null || !s.contains(marker)) return;
        String value;
        try {
            int i = s.indexOf(marker);
            int end = s.indexOf(')', i);
            if (end < 0) end = s.length();
            value = s.substring(i, end);
        } catch (Exception e) { return; }

        try {
            java.net.URI uri = java.net.URI.create(value);
            String q = uri.getQuery();
            java.util.Map<String,String> qp = java.util.Arrays.stream(q.split("&"))
                .map(p -> p.split("=",2))
                .collect(java.util.stream.Collectors.toMap(a -> a[0], a -> a.length>1?a[1]:""));
            int x = Integer.parseInt(qp.getOrDefault("x","0"));
            int y = Integer.parseInt(qp.getOrDefault("y","0"));
            int z = Integer.parseInt(qp.getOrDefault("z","0"));
            long t = Long.parseLong(qp.getOrDefault("t", String.valueOf(System.currentTimeMillis())));

            var mods = Modules.get();
            if (mods != null) {
                var cs = mods.get(ChatSigns.class);
                boolean nowOn = cs.toggleClickESP(new BlockPos(x,y,z), t);
                if (cs.chatFeedback) LogUtil.info((nowOn ? "ClickESP ON at " : "ClickESP OFF at ") + x + "," + y + "," + z, "chatsigns");
            }
            cir.setReturnValue(true);
            cir.cancel();
        } catch (Exception ignored) { }
    }
}
