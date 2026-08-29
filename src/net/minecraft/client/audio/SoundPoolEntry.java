package net.minecraft.client.audio;

import net.minecraft.util.ResourceLocation;

public class SoundPoolEntry {

    private final ResourceLocation location;
    private double pitch;
    private double volume;

    public SoundPoolEntry(ResourceLocation locationIn, double pitchIn, double volumeIn) {
        this.location = locationIn;
        this.pitch = pitchIn;
        this.volume = volumeIn;
    }

    public SoundPoolEntry(SoundPoolEntry locationIn) {
        this.location = locationIn.location;
        this.pitch = locationIn.pitch;
        this.volume = locationIn.volume;
    }

    public ResourceLocation getSoundPoolEntryLocation() {
        return this.location;
    }

    public double getPitch() {
        return this.pitch;
    }

    public void setPitch(double pitchIn) {
        this.pitch = pitchIn;
    }

    public double getVolume() {
        return this.volume;
    }

    public void setVolume(double volumeIn) {
        this.volume = volumeIn;
    }

}
