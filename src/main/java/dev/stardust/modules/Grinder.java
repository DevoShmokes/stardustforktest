package dev.stardust.modules;

import dev.stardust.Stardust;
import dev.stardust.mixin.accessor.GrindstoneScreenHandlerAccessor;
import dev.stardust.util.MsgUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.GrindstoneScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class Grinder extends Module {
    public Grinder() { super(Stardust.CATEGORY, "Grinder", "Automatically grinds enchantments off selected items in the grindstone."); }

    public enum ModuleMode { Packet, Interact }

    private final Setting<ModuleMode> moduleMode = settings.getDefaultGroup().add(
        new EnumSetting.Builder<ModuleMode>()
            .name("mode")
            .description("Packet is faster; Interact mimics UI pacing.")
            .defaultValue(ModuleMode.Packet)
            .build()
    );
    private final Setting<List<Item>> itemList = settings.getDefaultGroup().add(
        new ItemListSetting.Builder()
            .name("Items")
            .description("Items to automatically grind enchantments from.")
            .filter(item -> item.getDefaultStack().isEnchantable())
            .build()
    );
    private final Setting<Boolean> grindNamed = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("grind-named-items")
            .description("Grind enchantments off items which have a custom name applied.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Boolean> combine = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("combine-items")
            .description("Combine alike items in the grindstone to process quicker. DESTROYS A PORTION OF THE INPUT ITEMS.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Boolean> muteGrindstone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("mute-grindstone")
            .description("Mute the grindstone sounds.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Boolean> closeOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("close-grindstone")
            .description("Automatically close the grindstone when no more enchantments can be removed.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Boolean> disableOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("disable-on-done")
            .description("Automatically disable the module when no more enchantments can be removed.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Boolean> pingOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("sound-ping")
            .description("Play a sound cue when there are no more enchantments to remove.")
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
    private final Setting<Integer> tickRate = settings.getDefaultGroup().add(
        new IntSetting.Builder()
            .name("tick-rate")
            .description("Increase this if the server is kicking you.")
            .min(0).max(1000)
            .sliderRange(0, 100)
            .defaultValue(2)
            .visible(() -> moduleMode.get().equals(ModuleMode.Interact))
            .build()
    );

    private int timer = 0;
    private boolean notified = false;
    private @Nullable ItemStack combinedItem = null;
    private @Nullable ItemStack currentTarget = null;
    private final IntArrayList projectedEmpty = new IntArrayList();
    private final IntArrayList processedSlots = new IntArrayList();

    private boolean hasValidItems(GrindstoneScreenHandler handler) {
        if (mc.player == null) return false;
        for (int n = 0; n < ((dev.stardust.mixin.accessor.PlayerInventoryAccessor) mc.player.getInventory()).getMain() /*private*/.size() + 3; n++) {
            if (n == 2) continue; // skip output slot
            if (isValidItem(handler.getSlot(n).getStack())) return true;
        }
        return false;
    }

    private boolean hasValidEnchantments(ItemStack stack) {
        if (!stack.hasEnchantments()) return false;
        Object2IntMap<RegistryEntry<Enchantment>> enchants = new Object2IntArrayMap<>();
        Utils.getEnchantments(stack, enchants);
        if (enchants.size() == 1 && Utils.hasEnchantment(stack, Enchantments.BINDING_CURSE)) return false;
        else if (enchants.size() == 1 && Utils.hasEnchantment(stack, Enchantments.VANISHING_CURSE)) return false;
        else if (enchants.size() == 2 && Utils.hasEnchantment(stack, Enchantments.BINDING_CURSE) && Utils.hasEnchantment(stack, Enchantments.VANISHING_CURSE)) return false;
        return !enchants.isEmpty();
    }

    private boolean isValidItem(ItemStack item) {
        return itemList.get().contains(item.getItem())
            && hasValidEnchantments(item)
            && (grindNamed.get() || !item.contains(DataComponentTypes.CUSTOM_NAME));
    }

    private int predictEmptySlot(GrindstoneScreenHandler handler) {
        if (mc.player == null) return -1;
        for (int n = ((dev.stardust.mixin.accessor.PlayerInventoryAccessor) mc.player.getInventory()).getMain() /*private*/.size() + 2; n >= 3; n--) {
            if (processedSlots.contains(n) && !projectedEmpty.contains(n)) continue;
            if (projectedEmpty.contains(n)) {
                projectedEmpty.rem(n);
                return n;
            } else if (handler.getSlot(n).getStack().isEmpty()) {
                processedSlots.add(n);
                return n;
            }
        }
        return -1;
    }

    private void finished() {
        if (mc.player == null) return;
        if (!notified) {
            if (chatFeedback) MsgUtil.sendModuleMsg("No more enchantments to grind away..!", this.name);
            if (pingOnDone.get()) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pingVolume.get().floatValue(), ThreadLocalRandom.current().nextFloat(0.69f, 1.337f));
        }
        notified = true;
        processedSlots.clear();
        projectedEmpty.clear();
        if (closeOnDone.get()) mc.player.closeHandledScreen();
        if (disableOnDone.get()) toggle();
    }

    private @Nullable Object generatePacket(GrindstoneScreenHandler handler) { return null; }

    @Override
    public void onDeactivate() {
        timer = 0;
        notified = false;
        combinedItem = null;
        currentTarget = null;
        projectedEmpty.clear();
        processedSlots.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (mc.currentScreen == null) { notified = false; return; }
        if (!(mc.currentScreen instanceof GrindstoneScreen)) return;
        if (!(mc.player.currentScreenHandler instanceof GrindstoneScreenHandler grindstone)) return;

        boolean isPacket = moduleMode.get() == ModuleMode.Packet;
        if (isPacket) {
            if (tickRate.get() > 0) { /* reuse tickRate for pacing if desired */ }
            // No pacing by default in packet mode
        } else {
            if (timer >= tickRate.get()) timer = 0; else { ++timer; return; }
        }

        ItemStack input1 = grindstone.getSlot(GrindstoneScreenHandler.INPUT_1_ID).getStack();
        ItemStack input2 = grindstone.getSlot(GrindstoneScreenHandler.INPUT_2_ID).getStack();
        ItemStack output = grindstone.getSlot(GrindstoneScreenHandler.OUTPUT_ID).getStack();

        if (!hasValidItems(grindstone)) finished();
        else if (input1.isEmpty() && input2.isEmpty()) {
            Item turboItem = null;
            for (int n = 3; n < ((dev.stardust.mixin.accessor.PlayerInventoryAccessor) mc.player.getInventory()).getMain() /*private*/.size() + 3; n++) {
                ItemStack stack = grindstone.getSlot(n).getStack();
                if (!hasValidEnchantments(stack)) continue;
                else if (!itemList.get().contains(stack.getItem())) continue;
                else if (stack.contains(DataComponentTypes.CUSTOM_NAME) && !grindNamed.get()) continue;
                if (combine.get() && turboItem != null && stack.getItem() != turboItem) continue;

                if (isPacket) mc.interactionManager.clickSlot(grindstone.syncId, n, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                else InvUtils.shiftClick().slotId(n);
                if (!combine.get()) return;
                if (turboItem != null) return;
                turboItem = stack.getItem();
            }
            if (!combine.get()) finished();
        } else if (!output.isEmpty() && (itemList.get().contains(input1.getItem()) || itemList.get().contains(input2.getItem()))) {
            if (!input1.isEmpty()) {
                if (!input1.contains(DataComponentTypes.CUSTOM_NAME) || grindNamed.get()) {
                    if (isPacket) mc.interactionManager.clickSlot(grindstone.syncId, GrindstoneScreenHandler.OUTPUT_ID, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                    else InvUtils.shiftClick().slotId(GrindstoneScreenHandler.OUTPUT_ID);
                }
            } else if (!input2.isEmpty()) {
                if (!input2.contains(DataComponentTypes.CUSTOM_NAME) || grindNamed.get()) {
                    if (isPacket) mc.interactionManager.clickSlot(grindstone.syncId, GrindstoneScreenHandler.OUTPUT_ID, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                    else InvUtils.shiftClick().slotId(GrindstoneScreenHandler.OUTPUT_ID);
                }
            }
        } else if (!input1.isEmpty() && !input2.isEmpty()) {
            if (isPacket) {
                mc.interactionManager.clickSlot(grindstone.syncId, GrindstoneScreenHandler.INPUT_1_ID, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                mc.interactionManager.clickSlot(grindstone.syncId, GrindstoneScreenHandler.INPUT_2_ID, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
            } else {
                InvUtils.shiftClick().slotId(GrindstoneScreenHandler.INPUT_1_ID);
                InvUtils.shiftClick().slotId(GrindstoneScreenHandler.INPUT_2_ID);
            }
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!muteGrindstone.get() || !(event.packet instanceof PlaySoundS2CPacket packet)) return;
        if (packet.getSound().value().equals(SoundEvents.BLOCK_GRINDSTONE_USE)) event.cancel();
    }
}
