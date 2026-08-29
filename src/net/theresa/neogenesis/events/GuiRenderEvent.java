package net.theresa.neogenesis.events;

import net.theresa.neogenesis.events.CancelableEvent;

public class GuiRenderEvent extends CancelableEvent<GuiRenderEvent> {

    public final double renderTicks;

    public GuiRenderEvent(double renderTicks) {
        this.renderTicks = renderTicks;
    }

}
