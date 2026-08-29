package net.theresa.neogenesis.events;

import net.theresa.neogenesis.ClientMain;

public class Event<T extends Event<T>> {

    public T call(EventManager eventManager) {
        eventManager.call(this);
        return (T) this;
    }

    public T call() {
        return this.call(ClientMain.eventManager);
    }

    public boolean canApply(Subscribe annotation) {
        return true;
    }

}