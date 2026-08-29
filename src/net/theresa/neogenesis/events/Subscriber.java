package net.theresa.neogenesis.events;

import org.jetbrains.annotations.NotNull;

public interface Subscriber
{
    @NotNull
    SubscribeEntry[] getSubscribeEntries();

    public static final class DefaultImpls
    {
        @NotNull
        public static SubscribeEntry[] getSubscribeEntries(@NotNull final Subscriber $this) {
            return new SubscribeEntry[0];
        }
    }
}