package net.theresa.neogenesis.events;

import net.theresa.neogenesis.events.CancelableEvent;

public class ChatSendEvent extends CancelableEvent<ChatSendEvent> {

    public final String initMessage;
    public final String initHistory;
    public final boolean initAddHistory;

    public String message;
    public String history;
    public boolean addHistory;

    public ChatSendEvent(String message, String history, boolean addHistory) {
        this.initMessage = message;
        this.initHistory = history;
        this.initAddHistory = addHistory;
        this.setMessage(message);
        this.setHistory(history);
        this.setAddHistory(addHistory);
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setHistory(String history) {
        this.history = history;
    }

    public void setAddHistory(boolean addHistory) {
        this.addHistory = addHistory;
    }

    public static class Player extends ChatSendEvent {

        public Player(String message, String history, boolean addHistory) {
            super(message, history, addHistory);
        }

    }

}
