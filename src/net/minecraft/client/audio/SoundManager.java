package net.minecraft.client.audio;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import io.netty.util.internal.ThreadLocalRandom;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import libsrc.paulscode.sound.SoundSystem;
import libsrc.paulscode.sound.SoundSystemConfig;
import libsrc.paulscode.sound.SoundSystemException;
import libsrc.paulscode.sound.SoundSystemLogger;
import libsrc.paulscode.sound.Source;
import libsrc.paulscode.sound.codecs.CodecJOrbis;
import libsrc.paulscode.sound.libraries.LibraryLWJGLOpenAL;

public class SoundManager {

    /** The marker used for logging */
    private static final Marker LOG_MARKER = MarkerManager.getMarker("SOUNDS");
    private static final Logger logger = LogManager.getLogger();

    /** A reference to the sound handler. */
    private final SoundHandler sndHandler;

    /** Reference to the GameSettings object. */
    private final GameSettings options;

    /** A reference to the sound system. */
    private SoundManager.SoundSystemStarterThread sndSystem;

    /** Set to true when the SoundManager has been initialised. */
    private boolean loaded;

    /** A counter for how long the sound manager has been running */
    private int playTime = 0;
    private final Map<String, ISound> playingSounds = HashBiMap.<String, ISound>create();
    private final Map<ISound, String> invPlayingSounds;
    private Map<ISound, SoundPoolEntry> playingSoundPoolEntries;
    private final Multimap<SoundCategory, String> categorySounds;
    private final List<ITickableSound> tickableSounds;
    private final Map<ISound, Integer> delayedSounds;
    private final Map<String, Integer> playingSoundsStopTime;

    public SoundManager(SoundHandler var1, GameSettings var2) {
        this.invPlayingSounds = ((BiMap)this.playingSounds).inverse();
        this.playingSoundPoolEntries = Maps.newHashMap();
        this.categorySounds = HashMultimap.create();
        this.tickableSounds = Lists.newArrayList();
        this.delayedSounds = Maps.newHashMap();
        this.playingSoundsStopTime = Maps.newHashMap();
        this.sndHandler = var1;
        this.options = var2;

        try {
            SoundSystemConfig.addLibrary(LibraryLWJGLOpenAL.class);
            SoundSystemConfig.setCodec("ogg", CodecJOrbis.class);
        } catch (SoundSystemException var4) {
            logger.error(LOG_MARKER, "Error linking with the LibraryJavaSound plug-in", var4);
        }

    }

    public void reloadSoundSystem() {
        this.unloadSoundSystem();
        this.loadSoundSystem();
    }

    private synchronized void loadSoundSystem() {
        if (!this.loaded) {
            try {
                (new Thread(new Runnable() {
                    public void run() {
                        SoundSystemConfig.setLogger(new SoundSystemLogger() {
                            public void message(String var1, int var2) {
                                if (!var1.isEmpty()) {
                                    SoundManager.logger.info(var1);
                                }

                            }

                            public void importantMessage(String var1, int var2) {
                                if (!var1.isEmpty()) {
                                    SoundManager.logger.warn(var1);
                                }

                            }

                            public void errorMessage(String var1, String var2, int var3) {
                                if (!var2.isEmpty()) {
                                    //SoundManager.logger.error("Error in class '" + var1 + "'");
                                    //SoundManager.logger.error(var2);
                                }

                            }
                        });
                        SoundManager.this.sndSystem = SoundManager.this.new SoundSystemStarterThread();
                        SoundManager.this.loaded = true;
                        SoundManager.this.sndSystem.setMasterVolume(SoundManager.this.options.getSoundLevel(SoundCategory.MASTER));
                        SoundManager.logger.info(SoundManager.LOG_MARKER, "Sound engine started");
                    }
                }, "Sound Library Loader")).start();
            } catch (RuntimeException var2) {
                logger.error(LOG_MARKER, "Error starting SoundSystem. Turning off sounds & music", var2);
                this.options.setSoundLevel(SoundCategory.MASTER, 0.0F);
                this.options.saveOptions();
            }
        }

    }

    private float getSoundCategoryVolume(SoundCategory var1) {
        return var1 != null && var1 != SoundCategory.MASTER ? this.options.getSoundLevel(var1) : 1.0F;
    }

    public void setSoundCategoryVolume(SoundCategory var1, float var2) {
        if (this.loaded) {
            if (var1 == SoundCategory.MASTER) {
                this.sndSystem.setMasterVolume(var2);
            } else {
                for(String var4 : this.categorySounds.get(var1)) {
                    ISound var5 = (ISound)this.playingSounds.get(var4);
                    float var6 = this.getNormalizedVolume(var5, (SoundPoolEntry)this.playingSoundPoolEntries.get(var5), var1);
                    if (var6 <= 0.0F) {
                        this.stopSound(var5);
                    } else {
                        this.sndSystem.setVolume(var4, var6);
                    }
                }
            }
        }

    }

    public void unloadSoundSystem() {
        if (this.loaded) {
            this.stopAllSounds();
            this.sndSystem.cleanup();
            this.loaded = false;
        }

    }

    public void stopAllSounds() {
        if (this.loaded) {
            for(String var2 : this.playingSounds.keySet()) {
                this.sndSystem.stop(var2);
            }

            this.playingSounds.clear();
            this.delayedSounds.clear();
            this.tickableSounds.clear();
            this.categorySounds.clear();
            this.playingSoundPoolEntries.clear();
            this.playingSoundsStopTime.clear();
        }

    }

    public void updateAllSounds() {
        ++this.playTime;

        for(ITickableSound var2 : this.tickableSounds) {
            var2.update();
            if (var2.isDonePlaying()) {
                this.stopSound(var2);
            } else {
                String var3 = (String)this.invPlayingSounds.get(var2);
                this.sndSystem.setVolume(var3, this.getNormalizedVolume(var2, (SoundPoolEntry)this.playingSoundPoolEntries.get(var2), this.sndHandler.getSound(var2.getSoundLocation()).getSoundCategory()));
                this.sndSystem.setPitch(var3, this.getNormalizedPitch(var2, (SoundPoolEntry)this.playingSoundPoolEntries.get(var2)));
                this.sndSystem.setPosition(var3, var2.getXPosF(), var2.getYPosF(), var2.getZPosF());
            }
        }

        Iterator var9 = this.playingSounds.entrySet().iterator();

        while(var9.hasNext()) {
            Map.Entry var10 = (Map.Entry)var9.next();
            String var12 = (String)var10.getKey();
            ISound var4 = (ISound)var10.getValue();
            if (!this.sndSystem.playing(var12)) {
                int var5 = (Integer)this.playingSoundsStopTime.get(var12);
                if (var5 <= this.playTime) {
                    int var6 = var4.getRepeatDelay();
                    if (var4.canRepeat() && var6 > 0) {
                        this.delayedSounds.put(var4, this.playTime + var6);
                    }

                    var9.remove();
                    logger.debug(LOG_MARKER, "Removed channel {} because it's not playing anymore", new Object[]{var12});
                    this.sndSystem.removeSource(var12);
                    this.playingSoundsStopTime.remove(var12);
                    this.playingSoundPoolEntries.remove(var4);

                    try {
                        this.categorySounds.remove(this.sndHandler.getSound(var4.getSoundLocation()).getSoundCategory(), var12);
                    } catch (RuntimeException var8) {
                    }

                    if (var4 instanceof ITickableSound) {
                        this.tickableSounds.remove(var4);
                    }
                }
            }
        }

        Iterator var11 = this.delayedSounds.entrySet().iterator();

        while(var11.hasNext()) {
            Map.Entry var13 = (Map.Entry)var11.next();
            if (this.playTime >= (Integer)var13.getValue()) {
                ISound var14 = (ISound)var13.getKey();
                if (var14 instanceof ITickableSound) {
                    ((ITickableSound)var14).update();
                }

                this.playSound(var14);
                var11.remove();
            }
        }

    }

    public boolean isSoundPlaying(ISound var1) {
        if (!this.loaded) {
            return false;
        } else {
            String var2 = (String)this.invPlayingSounds.get(var1);
            return var2 != null && (this.sndSystem.playing(var2) || this.playingSoundsStopTime.containsKey(var2) && (Integer)this.playingSoundsStopTime.get(var2) <= this.playTime);
        }
    }

    public void stopSound(ISound var1) {
        if (this.loaded) {
            String var2 = (String)this.invPlayingSounds.get(var1);
            if (var2 != null) {
                this.sndSystem.stop(var2);
            }
        }

    }

    public void playSound(ISound var1) {
        if (this.loaded) {
            if (var1 instanceof PositionedSoundRecord) {
                PositionedSoundRecord var2 = (PositionedSoundRecord)var1;
                //if (ModManager.modtog.getSettingOn("enable_no_record") && var2.isRecord) {
                //    return;
                //}
            }

            if (this.sndSystem.getMasterVolume() > 0.0F) {
                SoundEventAccessorComposite var13 = this.sndHandler.getSound(var1.getSoundLocation());
                if (var13 != null) {
                    SoundPoolEntry var3 = var13.cloneEntry();
                    if (var3 != SoundHandler.missing_sound) {
                        float var4 = var1.getVolume();
                        float var5 = 16.0F;
                        if (var4 > 1.0F) {
                            var5 *= var4;
                        }

                        SoundCategory var6 = var13.getSoundCategory();
                        float var7 = this.getNormalizedVolume(var1, var3, var6);
                        double var8 = (double)this.getNormalizedPitch(var1, var3);
                        ResourceLocation var10 = var3.getSoundPoolEntryLocation();
                        if (var7 != 0.0F) {
                            boolean var11 = var1.canRepeat() && var1.getRepeatDelay() == 0;
                            String var12 = MathHelper.getRandomUuid(ThreadLocalRandom.current()).toString();
                            if (var10.toString().toLowerCase().contains("yqlossclientstreamaudio")) {
                                this.sndSystem.newStreamingSource(false, var12, getURLForSoundResource(var10), var10.toString(), var11, var1.getXPosF(), var1.getYPosF(), var1.getZPosF(), var1.getAttenuationType().getTypeInt(), var5);
                            } else {
                                this.sndSystem.newSource(false, var12, getURLForSoundResource(var10), var10.toString(), var11, var1.getXPosF(), var1.getYPosF(), var1.getZPosF(), var1.getAttenuationType().getTypeInt(), var5);
                            }

                            this.sndSystem.setPitch(var12, (float)var8);
                            this.sndSystem.setVolume(var12, var7);
                            this.sndSystem.play(var12);
                            this.playingSoundsStopTime.put(var12, this.playTime + 20);
                            this.playingSounds.put(var12, var1);
                            this.playingSoundPoolEntries.put(var1, var3);
                            if (var6 != SoundCategory.MASTER) {
                                this.categorySounds.put(var6, var12);
                            }

                            if (var1 instanceof ITickableSound) {
                                this.tickableSounds.add((ITickableSound)var1);
                            }
                        }
                    }
                }
            }
        }

    }

    private float getNormalizedPitch(ISound var1, SoundPoolEntry var2) {
        return (float)MathHelper.clamp_double((double)var1.getPitch() * var2.getPitch(), (double)0.5F, (double)2.0F);
    }

    private float getNormalizedVolume(ISound var1, SoundPoolEntry var2, SoundCategory var3) {
        return (float)MathHelper.clamp_double((double)var1.getVolume() * var2.getVolume(), (double)0.0F, (double)1.0F) * this.getSoundCategoryVolume(var3);
    }

    public void pauseAllSounds() {
        for(String var2 : this.playingSounds.keySet()) {
            logger.debug(LOG_MARKER, "Pausing channel {}", new Object[]{var2});
            this.sndSystem.pause(var2);
        }

    }

    public void resumeAllSounds() {
        for(String var2 : this.playingSounds.keySet()) {
            logger.debug(LOG_MARKER, "Resuming channel {}", new Object[]{var2});
            this.sndSystem.play(var2);
        }

    }

    public void playDelayedSound(ISound var1, int var2) {
        this.delayedSounds.put(var1, this.playTime + var2);
    }

    private static URL getURLForSoundResource(final ResourceLocation var0) {
        String var1 = String.format("%s:%s:%s", "mcsounddomain", var0.getResourceDomain(), var0.getResourcePath());
        URLStreamHandler var2 = new URLStreamHandler() {
            protected URLConnection openConnection(URL var1) {
                return new URLConnection(var1) {
                    public void connect() throws IOException {
                    }

                    public InputStream getInputStream() throws IOException {
                        return Minecraft.getMinecraft().getResourceManager().getResource(var0).getInputStream();
                    }
                };
            }
        };

        try {
            return new URL((URL)null, var1, var2);
        } catch (MalformedURLException var4) {
            throw new Error("TODO: Sanely handle url exception! :D");
        }
    }

    public void setListener(EntityPlayer var1, float var2) {
        if (this.loaded && var1 != null) {
            float var3 = var1.prevRotationPitch + (var1.rotationPitch - var1.prevRotationPitch) * var2;
            float var4 = var1.prevRotationYaw + (var1.rotationYaw - var1.prevRotationYaw) * var2;
            double var5 = var1.prevPosX + (var1.posX - var1.prevPosX) * (double)var2;
            double var7 = var1.prevPosY + (var1.posY - var1.prevPosY) * (double)var2 + (double)var1.getEyeHeight();
            double var9 = var1.prevPosZ + (var1.posZ - var1.prevPosZ) * (double)var2;
            float var11 = MathHelper.cos((var4 + 90.0F) * ((float)Math.PI / 180F));
            float var12 = MathHelper.sin((var4 + 90.0F) * ((float)Math.PI / 180F));
            float var13 = MathHelper.cos(-var3 * ((float)Math.PI / 180F));
            float var14 = MathHelper.sin(-var3 * ((float)Math.PI / 180F));
            float var15 = MathHelper.cos((-var3 + 90.0F) * ((float)Math.PI / 180F));
            float var16 = MathHelper.sin((-var3 + 90.0F) * ((float)Math.PI / 180F));
            float var17 = var11 * var13;
            float var18 = var12 * var13;
            float var19 = var11 * var15;
            float var20 = var12 * var15;
            this.sndSystem.setListenerPosition((float)var5, (float)var7, (float)var9);
            this.sndSystem.setListenerOrientation(var17, var14, var18, var19, var16, var20);
        }

    }

    class SoundSystemStarterThread extends SoundSystem {
        private SoundSystemStarterThread() {
        }

        public boolean playing(String var1) {
            synchronized(SoundSystemConfig.THREAD_SYNC) {
                if (this.soundLibrary == null) {
                    return false;
                } else {
                    Source var3 = (Source)this.soundLibrary.getSources().get(var1);
                    return var3 != null && (var3.playing() || var3.paused() || var3.preLoad);
                }
            }
        }
    }
}
