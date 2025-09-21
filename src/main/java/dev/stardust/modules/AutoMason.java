package dev.stardust.modules;

import dev.stardust.Stardust;
import dev.stardust.util.MsgUtil;
import dev.stardust.util.StonecutterUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.recipe.display.CuttingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.context.ContextParameterMap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Automates masonry interactions with the stonecutter.
 */
public class AutoMason extends Module {
    public AutoMason() { super(Stardust.CATEGORY, "AutoMason", "Automates masonry interactions with the stonecutter."); }

    public enum Mode { Packet, Interact }

    private final Setting<Mode> moduleMode = settings.getDefaultGroup().add(
        new EnumSetting.Builder<Mode>()
            .name("mode")
            .description("Packet is faster; Interact mimics UI pacing.")
            .defaultValue(Mode.Packet)
            .build()
    );
    private final Setting<Integer> batchDelay = settings.getDefaultGroup().add(
        new IntSetting.Builder()
            .name("packet-delay")
            .description("Increase this if the server is kicking you.")
            .min(0).max(1000)
            .sliderRange(0, 50)
            .defaultValue(1)
            .visible(() -> moduleMode.get().equals(Mode.Packet))
            .build()
    );
    private final Setting<Integer> tickRate = settings.getDefaultGroup().add(
        new IntSetting.Builder()
            .name("tick-rate")
            .description("Tick rate for interact mode.")
            .min(0).max(1000)
            .sliderRange(0, 100)
            .defaultValue(4)
            .visible(() -> moduleMode.get().equals(Mode.Interact))
            .build()
    );

    private final Setting<List<Item>> itemList = settings.getDefaultGroup().add(
        new ItemListSetting.Builder()
            .name("target-items")
            .description("Which target items you wish to craft in the Stonecutter.")
            .filter(item -> StonecutterUtil.STONECUTTER_BLOCKS.values().stream().anyMatch(v -> v.contains(item)))
            .build()
    );
    private final Setting<Boolean> muteCutter = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("mute-stonecutter")
            .description("Mute the stonecutter sounds.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Boolean> closeOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("close-stonecutter")
            .description("Automatically close the stonecutter screen when no more blocks can be crafted.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Boolean> disableOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("disable-when-done")
            .description("Automatically disable the module when no more blocks can be crafted.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Boolean> pingOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("sound-ping")
            .description("Play a sound cue when there are no more blocks to be crafted.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Double> pingVolume = settings.getDefaultGroup().add(
        new DoubleSetting.Builder()
            .name("ping-volume")
            .sliderMin(0.0)
            .sliderMax(5.0)
            .defaultValue(0.5)
            .visible(pingOnDone::get)
            .build()
    );

    private int timer = 0;
    private boolean notified = false;
    private final IntArrayList processedSlots = new IntArrayList();
    private final IntArrayList projectedEmpty = new IntArrayList();

    @Override
    public void onDeactivate() {
        timer = 0;
        notified = false;
        processedSlots.clear();
        projectedEmpty.clear();
    }

    @EventHandler
    private void onScreenOpen(OpenScreenEvent event) {
        if (event.screen instanceof StonecutterScreen) notified = false;
    }

    @EventHandler
    private void onSoundPlay(PlaySoundEvent event) {
        if (!muteCutter.get()) return;
        if (event.sound.getId().equals(SoundEvents.UI_STONECUTTER_TAKE_RESULT.id())) event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.getNetworkHandler() == null) return;
        if (mc.player == null || mc.world == null) return;
        if (!(mc.player.currentScreenHandler instanceof StonecutterScreenHandler cutter)) return;

        boolean isPacket = moduleMode.get() == Mode.Packet;
        if (isPacket) {
            // Packet mode uses batchDelay pacing
            if (batchDelay.get() > 0) {
                if (timer >= batchDelay.get()) timer = 0; else { ++timer; return; }
            }
        } else {
            // Interact mode pacing
            if (timer >= tickRate.get()) timer = 0; else { ++timer; return; }
        }

        if (itemList.get().isEmpty()) {
            if (!notified) {
                MsgUtil.sendModuleMsg("No target items selected.", this.name);
                if (pingOnDone.get()) {
                    mc.player.playSound(
                        SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                        pingVolume.get().floatValue(),
                        ThreadLocalRandom.current().nextFloat(0.69f, 1.337f)
                    );
                }
            }
            notified = true;
            finished();
            return;
        }

        ItemStack input = cutter.getSlot(StonecutterScreenHandler.INPUT_ID).getStack();
        ItemStack output = cutter.getSlot(StonecutterScreenHandler.OUTPUT_ID).getStack();

        if (!hasValidItems(cutter)) {
            finished();
        } else if (input.isEmpty() && output.isEmpty()) {
            for (int n = 2; n < ((dev.stardust.mixin.accessor.PlayerInventoryAccessor) mc.player.getInventory()).getMain() /*private*/.size() + 2; n++) {
                ItemStack stack = cutter.getSlot(n).getStack();
                if (!isValidItem(stack)) continue;
                if (isPacket) mc.interactionManager.clickSlot(cutter.syncId, n, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                else InvUtils.shiftClick().slotId(n);
                if (isPacket && batchDelay.get() > 0) return; // honor pacing
            }
        } else if (output.isEmpty()) {
            CuttingRecipeDisplay.Grouping<StonecuttingRecipe> available = mc.world
                .getRecipeManager().getStonecutterRecipes().filter(input);
            ContextParameterMap ctx = SlotDisplayContexts.createParameters(mc.world);

            boolean found = false;
            for (int n = 0; n < available.entries().size(); n++) {
                CuttingRecipeDisplay.GroupEntry<StonecuttingRecipe> entry = available.entries().get(n);
                ItemStack recipeStack = entry.recipe().optionDisplay().getFirst(ctx);
                if (recipeStack.isEmpty()) continue;
                if (itemList.get().contains(recipeStack.getItem())) {
                    found = true;
                    cutter.onButtonClick(mc.player, n);
                    if (isPacket) mc.interactionManager.clickButton(cutter.syncId, n);
                    else mc.getNetworkHandler().getConnection().send(new ButtonClickC2SPacket(cutter.syncId, n), null, true);
                    break;
                }
            }

            if (!found) {
                if (!notified) {
                    notified = true;
                    MsgUtil.sendModuleMsg("Desired recipe not found.", this.name);
                    if (pingOnDone.get()) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pingVolume.get().floatValue(), ThreadLocalRandom.current().nextFloat(0.69f, 1.337f));
                }
                finished();
            }
        } else {
            if (isPacket) mc.interactionManager.clickSlot(cutter.syncId, StonecutterScreenHandler.OUTPUT_ID, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
            else InvUtils.shiftClick().slotId(StonecutterScreenHandler.OUTPUT_ID);
        }
    }

    private void finished() {
        if (mc.player == null) return;
        if (!notified) {
            if (chatFeedback) MsgUtil.sendModuleMsg("No more items to craft.", this.name);
            if (pingOnDone.get()) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pingVolume.get().floatValue(), ThreadLocalRandom.current().nextFloat(0.69f, 1.337f));
        }
        notified = true;
        processedSlots.clear();
        projectedEmpty.clear();
        if (closeOnDone.get()) mc.player.closeHandledScreen();
        if (disableOnDone.get()) toggle();
    }

    @SuppressWarnings("unused")
    private @Nullable Object generatePacket(StonecutterScreenHandler handler) { return null; }

    private boolean hasValidItems(StonecutterScreenHandler handler) {
        if (mc.player == null) return false;
        for (int n = 0; n < ((dev.stardust.mixin.accessor.PlayerInventoryAccessor) mc.player.getInventory()).getMain() /*private*/.size() + 2; n++) {
            if (n == 1) continue; // skip output slot
            if (isValidItem(handler.getSlot(n).getStack())) return true;
        }
        return false;
    }

    private boolean isValidItem(ItemStack stack) {
        if (itemList.get().isEmpty()) return false;
        if (stack.isEmpty() || stack.isOf(Items.AIR)) return false;
        if (!StonecutterUtil.STONECUTTER_BLOCKS.containsKey(stack.getItem())) return false;
        return StonecutterUtil.STONECUTTER_BLOCKS.get(stack.getItem()).stream().anyMatch(item -> itemList.get().contains(item));
    }
}
