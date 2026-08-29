package net.theresa.neogenesis.events;

import net.theresa.neogenesis.events.Event;

public class LoopEvent extends Event<LoopEvent> {

    public static long currentCounter = 0L;
    public static double partialTicks = 0.0;
    public static double renderTicks = 0.0;

    public static double ticksPartial() {
        return TickEvent.currentCounter + partialTicks;
    }

    public static double ticksRender() {
        return TickEvent.currentCounter + renderTicks;
    }

    public final long counter;

    private LoopEvent(long counter) {
        this.counter = counter;
    }

    private LoopEvent() {
        this(currentCounter);
    }

    public static class Pre extends LoopEvent {

        public Pre(double minecraftPartialTicks, double minecraftRenderTicks) {
            super(++currentCounter);
            partialTicks = minecraftPartialTicks;
            renderTicks = minecraftRenderTicks;
        }

    }

    public static class Post extends LoopEvent {

        public Post() {
            super();
        }

    }

}
