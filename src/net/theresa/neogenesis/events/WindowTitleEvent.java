package net.theresa.neogenesis.events;

import net.theresa.neogenesis.events.Event;

public class WindowTitleEvent extends Event<WindowTitleEvent> {

    public final String initTitle;

    public String title;

    public WindowTitleEvent(String title) {
        this.initTitle = title;
        this.title = title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

}
