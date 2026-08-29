package net.theresa.neogenesis.events;

public class CancelableEvent<T extends CancelableEvent<T>> extends Event<T> {

    public boolean isCanceled = false;

    public void setCanceled(boolean canceled) {
        this.isCanceled = canceled;
    }

    @Override
    public boolean canApply(Subscribe annotation) {
        return !this.isCanceled || !annotation.checkCanceled();
    }

}
