package net.theresa.neogenesis.events;

import net.theresa.neogenesis.events.CancelableEvent;
import net.theresa.neogenesis.utils.Builder;

import java.util.Collection;
import java.util.Set;

public class ChatCompletionEvent extends CancelableEvent<ChatCompletionEvent> {

    public final String initMessage;

    public String message;
    public Set<String> completions = Builder.hashSet();

    public ChatCompletionEvent(String message) {
        this.initMessage = message;
        this.setMessage(message);
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void addCompletions(Collection<String> completions) {
        this.completions.addAll(completions);
    }

    public void addCompletions(String... completions) {
        this.completions.addAll(Builder.hashSet(completions));
    }

}
