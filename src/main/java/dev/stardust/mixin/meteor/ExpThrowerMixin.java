package dev.stardust.mixin.meteor;

import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.registry.tag.ItemTags;
import java.util.concurrent.ThreadLocalRandom;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.component.DataComponentTypes;
import org.spongepowered.asm.mixin.injection.Inject;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import meteordevelopment.meteorclient.systems.modules.player.EXPThrower;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.item.Item;

/**
 * @author Tas [0xTas] <root@0xTas.dev>
 **/
@Mixin(value = EXPThrower.class, remap = false)
public abstract class ExpThrowerMixin extends Module implements dev.stardust.accessor.ExpThrowerAutoOpenAccessor {
    public ExpThrowerMixin(Category category, String name, String description) {
        super(category, name, description);
    }

    @Unique
    private @Nullable Setting<Integer> levelCap = null;
    @Unique
    private @Nullable Setting<Boolean> autoToggle = null;
    @Unique
    private @Nullable Setting<Boolean> hotbarSwap = null;
    @Unique
    private @Nullable Setting<Boolean> autoOpen = null;
    @Unique
    private Float stardust$prevPitch = null;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void addLevelCapSetting(CallbackInfo ci) {
        levelCap = this.settings.getDefaultGroup().add(
            new IntSetting.Builder()
                .name("level-cap")
                .description("The level to stop throwing exp bottles at (leave on 0 for unlimited).")
                .min(0).sliderRange(0, 69)
                .defaultValue(0)
                .build()
        );
        autoToggle = this.settings.getDefaultGroup().add(
            new BoolSetting.Builder()
                .name("auto-toggle")
                .description("Automatically disable the module when the level cap is reached.")
                .defaultValue(false)
                .visible(() -> levelCap != null && levelCap.get() > 0)
                .build()
        );
        hotbarSwap = this.settings.getDefaultGroup().add(
            new BoolSetting.Builder()
                .name("hotbar-swap")
                .description("Swap xp from your inventory to your hotbar if none already occupies it.")
                .defaultValue(false)
                .build()
        );
        autoOpen = this.settings.getDefaultGroup().add(
            new BoolSetting.Builder()
                .name("auto-open")
                .description("After disabling at level cap, right-click once to reopen the anvil (or interact).")
                .defaultValue(true)
                .build()
        );
    }

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void headHotbarSwapAndGuards(CallbackInfo ci) {
        if (mc.player == null) return;

        // Always ensure XP bottle is selected if it exists in hotbar
        FindItemResult hotbarBottle = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
        if (hotbarBottle.found()) {
            int selected = ((dev.stardust.mixin.accessor.PlayerInventoryAccessor) mc.player.getInventory()).getSelectedSlot();
            int slotId = hotbarBottle.slot();
            int hotbarIndex = slotId >= 36 ? slotId - 36 : slotId; // normalize to 0..8
            if (hotbarIndex >= 0 && hotbarIndex <= 8 && hotbarIndex != selected) {
                ((dev.stardust.mixin.accessor.PlayerInventoryAccessor) mc.player.getInventory()).setSelectedSlot(hotbarIndex);
                if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarIndex));
            }
            return; // Let base module perform the actual throw logic this tick
        }

        // If hotbar-swap is enabled, try to bring a bottle into the hotbar
        if (hotbarSwap != null && hotbarSwap.get()) {
            FindItemResult invBottle = InvUtils.find(Items.EXPERIENCE_BOTTLE);
            if (invBottle.found()) {
                FindItemResult emptySlot = InvUtils.findInHotbar(ItemStack::isEmpty);
                if (emptySlot.found()) InvUtils.move().from(invBottle.slot()).to(emptySlot.slot());
                else {
                    FindItemResult nonCriticalSlot = InvUtils.findInHotbar(stack -> !stack.contains(DataComponentTypes.TOOL) && !(stack.isIn(ItemTags.WEAPON_ENCHANTABLE)) && !(stack.contains(DataComponentTypes.FOOD)));
                    if (nonCriticalSlot.found()) InvUtils.move().from(invBottle.slot()).to(nonCriticalSlot.slot());
                    else {
                        int luckySlot = ThreadLocalRandom.current().nextInt(9);
                        InvUtils.move().from(invBottle.slot()).to(luckySlot);
                    }
                }
                // We moved an XP bottle; cancel so base logic can proceed next tick
                ci.cancel();
            }
        }
    }

    @Inject(method = "onActivate", at = @At("HEAD"))
    private void stardust$capturePitch(CallbackInfo ci) {
        if (mc.player != null) stardust$prevPitch = mc.player.getPitch();
    }

    @Inject(method = "onDeactivate", at = @At("TAIL"))
    private void stardust$restorePitch(CallbackInfo ci) {
        if (mc.player != null && stardust$prevPitch != null) {
            mc.player.setPitch(stardust$prevPitch);
        }
        stardust$prevPitch = null;
    }

    @Inject(method = "onTick", at = @At("TAIL"))
    private void tailStopAtLevelCap(CallbackInfo ci) {
        if (mc.player == null) return;
        if (levelCap == null || levelCap.get() == 0) return;
        if (mc.player.experienceLevel >= levelCap.get()) {
            if (autoToggle != null && autoToggle.get()) this.toggle();
        }
    }

    @Inject(method = "onTick", at = @At("TAIL"))
    private void tailForceThrow(CallbackInfo ci) {
        if (mc.player == null || mc.interactionManager == null) return;

        // If we're holding an XP bottle in either hand, use it, but respect level-cap if set.
        if (levelCap != null && levelCap.get() > 0 && mc.player.experienceLevel >= levelCap.get()) return;
        Item main = mc.player.getMainHandStack().getItem();
        Item off = mc.player.getOffHandStack().getItem();

        if (main == Items.EXPERIENCE_BOTTLE) {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        } else if (off == Items.EXPERIENCE_BOTTLE) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
        }
    }

    @Override
    public boolean stardust$isAutoOpenEnabled() {
        return autoOpen != null && autoOpen.get();
    }
}
