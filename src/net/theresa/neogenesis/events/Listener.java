package net.theresa.neogenesis.events;

public interface Listener
{
    default <T extends Event<T>> boolean canListen(Event<T> event)
    {
        return true;
    }
}
