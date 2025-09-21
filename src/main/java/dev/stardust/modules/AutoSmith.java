package dev.stardust.modules;

import dev.stardust.Stardust;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;

public class AutoSmith extends Module {
    private final Setting<Boolean> muteSmithy = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("mute-smithing-sounds")
            .description("Mute the smithing table use sound.")
            .defaultValue(false)
            .build()
    );

    public AutoSmith() {
        super(Stardust.CATEGORY, "AutoSmith", "Smithing helper (Interact-only stub for 1.21.8).");
    }

    public boolean muteSmithy() {
        return muteSmithy.get();
    }

    @EventHandler
    private void onSoundPlay(PlaySoundEvent event) {
        if (!muteSmithy.get()) return;
        if (event.sound.getId().equals(SoundEvents.BLOCK_SMITHING_TABLE_USE.id())) event.cancel();
    }
}
