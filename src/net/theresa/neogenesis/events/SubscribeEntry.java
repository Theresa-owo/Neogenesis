package net.theresa.neogenesis.events;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.Intrinsics;
import kotlin.reflect.KClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SubscribeEntry
{
    @NotNull
    public static final Companion Companion;
    @NotNull
    private final KClass<? extends Event> EventClass;
    @NotNull
    private final Function1<Event, Unit> EventHandler;
    private final int Priority;

    public SubscribeEntry(@NotNull final KClass<? extends Event> EventClass, @NotNull final Function1<? super Event, Unit> EventHandler, final int Priority) {
        Intrinsics.checkNotNullParameter((Object)EventClass, "EventClass");
        Intrinsics.checkNotNullParameter((Object)EventHandler, "EventHandler");
        this.EventClass = EventClass;
        this.EventHandler = (Function1<Event, Unit>)EventHandler;
        this.Priority = Priority;
    }

    @NotNull
    public final KClass<? extends Event> getEventClass() {
        return this.EventClass;
    }

    @NotNull
    public final Function1<Event, Unit> getEventHandler() {
        return this.EventHandler;
    }

    public final int getPriority() {
        return this.Priority;
    }

    @NotNull
    public final KClass<? extends Event> component1() {
        return this.EventClass;
    }

    @NotNull
    public final Function1<Event, Unit> component2() {
        return this.EventHandler;
    }

    public final int component3() {
        return this.Priority;
    }

    @NotNull
    public final SubscribeEntry copy(@NotNull final KClass<? extends Event> EventClass, @NotNull final Function1<? super Event, Unit> EventHandler, final int Priority) {
        Intrinsics.checkNotNullParameter((Object)EventClass, "EventClass");
        Intrinsics.checkNotNullParameter((Object)EventHandler, "EventHandler");
        return new SubscribeEntry(EventClass, EventHandler, Priority);
    }

    @NotNull
    @Override
    public String toString() {
        return "SubscribeEntry(EventClass=" + this.EventClass + ", EventHandler=" + this.EventHandler + ", Priority=" + this.Priority + ')';
    }

    @Override
    public int hashCode() {
        int result = this.EventClass.hashCode();
        result = result * 31 + this.EventHandler.hashCode();
        result = result * 31 + Integer.hashCode(this.Priority);
        return result;
    }

    @Override
    public boolean equals(@Nullable final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SubscribeEntry)) {
            return false;
        }
        final SubscribeEntry subscribeEntry = (SubscribeEntry)other;
        return Intrinsics.areEqual((Object)this.EventClass, (Object)subscribeEntry.EventClass) && Intrinsics.areEqual((Object)this.EventHandler, (Object)subscribeEntry.EventHandler) && this.Priority == subscribeEntry.Priority;
    }

    static {
        Companion = new Companion(null);
    }

    public static final class Companion
    {
        private Companion(Object o) {
        }
    }
}
