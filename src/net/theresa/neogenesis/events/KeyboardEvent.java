package net.theresa.neogenesis.events;

public class KeyboardEvent extends CancelableEvent<KeyboardEvent> {

    public final char character;
    public final int keyCode;
    public final boolean pressed;

    public KeyboardEvent(char character, int keyCode, boolean pressed) {
        this.character = character;
        this.keyCode = keyCode;
        this.pressed = pressed;
    }
}
