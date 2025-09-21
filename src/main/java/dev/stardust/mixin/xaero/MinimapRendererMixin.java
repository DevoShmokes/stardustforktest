package dev.stardust.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Pseudo mixin for Xaero Minimap renderer to restore presence when Xaero is installed.
 * Targets are string-based (remap=false) to avoid hard dependency during compilation.
 */
@Pseudo
@Mixin(targets = "xaero.common.gui.render.MinimapRenderer", remap = false)
public class MinimapRendererMixin {
    // Intentionally empty; add hooks if/when needed.
}

