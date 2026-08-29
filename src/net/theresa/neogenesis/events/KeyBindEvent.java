package net.theresa.neogenesis.events;

import net.minecraft.client.settings.KeyBinding;

public class KeyBindEvent extends Event {

    public KeyBinding keyBind;
    public boolean keyPressed;

    public KeyBindEvent(KeyBinding keyBind, boolean keyPressed) {
        this.keyBind = keyBind;
        this.keyPressed = keyPressed;
    }
}