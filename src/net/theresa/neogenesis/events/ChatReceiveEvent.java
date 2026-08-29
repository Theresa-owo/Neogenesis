package net.theresa.neogenesis.events;

import net.theresa.neogenesis.utils.RawString;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

public class ChatReceiveEvent extends CancelableEvent<ChatReceiveEvent> {

    // TODO: Refactor

    public IChatComponent component;
    public RawString message;

    public ChatReceiveEvent(IChatComponent component) {
        this.setMessage(component);
    }

    public void setMessage(IChatComponent component) {
        this.component = component;
        this.message = new RawString(component.getFormattedText());
    }

    public void setMessage(String message) {
        this.component = new ChatComponentText(message);
        this.message = new RawString(message);
    }

    public static class Packet extends ChatReceiveEvent {

        public Packet(IChatComponent component) {
            super(component);
        }

    }

}
