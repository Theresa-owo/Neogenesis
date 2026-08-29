package net.theresa.neogenesis.events;

public class TickEvent extends Event<TickEvent> {

    public static long currentCounter = 0L;

    public final long counter;

    private TickEvent(long counter) {
        this.counter = counter;
    }

    private TickEvent() {
        this(currentCounter);
    }


    public static class Pre extends TickEvent {

        public Pre()
        {
            super(++currentCounter);
        }
    }

    public static class Post extends TickEvent {

        public Post()
        {
            super();
        }

    }
}
