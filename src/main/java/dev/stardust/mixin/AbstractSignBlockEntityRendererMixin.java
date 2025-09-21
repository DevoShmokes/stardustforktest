package dev.stardust.mixin;

import java.util.Arrays;
import net.minecraft.text.Text;
import java.util.stream.Collectors;
import dev.stardust.modules.AntiToS;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.block.entity.SignText;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.AbstractSignBlockEntityRenderer;

/**
 * @author Tas [0xTas] <root@0xTas.dev>
 **/
@Mixin(AbstractSignBlockEntityRenderer.class)
public abstract class AbstractSignBlockEntityRendererMixin implements BlockEntityRenderer<SignBlockEntity> {

    // See AntiToS.java
    @ModifyVariable(method = "renderText", at = @At("HEAD"), argsOnly = true)
    private SignText modifyRenderedText(SignText signText) {
        Modules modules = Modules.get();
        if (modules == null ) return signText;
        AntiToS antiToS = modules.get(AntiToS.class);
        if (!antiToS.isActive()) return signText;

        String testText = Arrays.stream(signText.getMessages(false))
            .map(Text::getString)
            .collect(Collectors.joining(" "))
            .trim();
        return antiToS.containsBlacklistedText(testText) ? antiToS.familyFriendlySignText(signText) : signText;
    }

    @Unique
    private boolean isCodySign(SignBlockEntity sbe) {
        SignText frontText = sbe.getFrontText();
        return Arrays.stream(frontText.getMessages(false)).anyMatch(msg -> msg.getString().contains("codysmile11") || msg.getString().toLowerCase().contains("has been here :)"));
    }
}
