package net.theresa.neogenesis.events;

import kotlin.jvm.internal.Intrinsics;
import net.theresa.neogenesis.utils.Builder;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventManager {

    private final Map<Class<Event<?>>, List<ListenerMethod>> cachedListenerMap = Builder.hashMap();
    private final Set<Class<?>> loadedClasses = Builder.hashSet();
    private final List<Listener> registeredListenerList = Builder.arrayList();

    public void register(Listener listener) {
        if (!this.registeredListenerList.contains(listener)) {
            this.registeredListenerList.add(listener);
            this.updateCache();
        }
    }

    public void unregister(Listener listener) {
        if (this.registeredListenerList.contains(listener)) {
            this.registeredListenerList.remove(listener);
            this.updateCache();
        }
    }

    private void addToCache(Class<Event<?>> eventClass, ListenerMethod listenerMethod) {
        if (!this.cachedListenerMap.containsKey(eventClass)) {
            this.cachedListenerMap.put(eventClass, new CopyOnWriteArrayList<>());
        }
        this.cachedListenerMap.get(eventClass).add(listenerMethod);
    }

    private void updateCache() {
        this.cachedListenerMap.clear();
        this.loadedClasses.clear();
        for (Listener listener : this.registeredListenerList) {
            for (Method method : listener.getClass().getMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    Subscribe annotation = method.getAnnotation(Subscribe.class);
                    Class<?>[] argumentClasses = method.getParameterTypes();
                    if (annotation != null && argumentClasses.length == 1 &&
                            Event.class.isAssignableFrom(argumentClasses[0])) {
                        method.setAccessible(true);
                        Class<Event<?>> clazz = (Class<Event<?>>) argumentClasses[0];
                        this.addToCache(clazz, new ListenerMethod(listener, method, annotation));
                    }
                }
            }
        }
        for (Class<Event<?>> clazz : this.cachedListenerMap.keySet().toArray(new Class[0])) {
            this.loadSuperClass(clazz);
        }
        for (Map.Entry<Class<Event<?>>, List<ListenerMethod>> entry : this.cachedListenerMap.entrySet()) {
            entry.getValue().sort(ListenerMethod.COMPARATOR);
        }
    }

    private void loadSuperClass(Class<?> clazz) {
        if (this.loadedClasses.contains(clazz)) {
            return;
        }
        this.loadedClasses.add(clazz);
        if (!this.cachedListenerMap.containsKey(clazz)) {
            this.cachedListenerMap.put((Class<Event<?>>) clazz, new CopyOnWriteArrayList<>());
        }
        Class<?> superClass = clazz.getSuperclass();
        if (Event.class.isAssignableFrom(superClass)) {
            this.loadSuperClass(superClass);
            this.cachedListenerMap.get(clazz).addAll(this.cachedListenerMap.get(superClass));
        }
    }

    public void call(Event<?> event) {
        Class<?> clazz = event.getClass();
        if (!this.cachedListenerMap.containsKey(clazz)) {
            this.loadSuperClass(clazz);
        }
        for (ListenerMethod listenerMethod : this.cachedListenerMap.get(event.getClass())) {
            listenerMethod.call(event);
        }
    }

    private static class ListenerMethod {

        private static final Comparator<ListenerMethod> COMPARATOR =
                Comparator.comparingInt(x -> x.annotation.priority());

        private final Listener listener;
        private final Method method;
        private final Subscribe annotation;

        private ListenerMethod(Listener listener, Method method, Subscribe annotation) {
            this.listener = listener;
            this.method = method;
            this.annotation = annotation;
        }

        private void call(Event<?> event) {
            try {
                if (event.canApply(this.annotation) && this.listener.canListen(event)) {
                    this.method.invoke(this.listener, event);
                }
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }

    }

}