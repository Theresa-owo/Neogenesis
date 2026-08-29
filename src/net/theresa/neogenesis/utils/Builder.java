package net.theresa.neogenesis.utils;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class Builder {

    public static <T> List<T> arrayList(T... values) {
        return new CopyOnWriteArrayList<>(values);
    }

    public static <T> List<T> constList(T... values) {
        return Collections.unmodifiableList(arrayList(values));
    }

    public static <T> List<T> arrayList(Collection<T> collection) {
        return new CopyOnWriteArrayList<>(collection);
    }

    public static <T> List<T> constList(Collection<T> collection) {
        return Collections.unmodifiableList(arrayList(collection));
    }

    public static <K, V> Map<K, V> hashMap(Object... values) {
        Map<K, V> map = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put((K) values[i], (V) values[i + 1]);
        }
        return map;
    }

    public static <K, V> Map<K, V> hashMap(Map<K, V> map) {
        return new HashMap<>(map);
    }

    public static <T> Set<T> hashSet(T... values) {
        return new HashSet<>(constList(values));
    }

    public static <T> Set<T> hashSet(Collection<T> collection) {
        return new HashSet<>(constList(collection));
    }

    public static String join(String joiner, Object... objects) {
        StringBuilder builder = new StringBuilder();
        for (Object object : objects) {
            builder.append(object);
            builder.append(joiner);
        }
        builder.delete(builder.length() - joiner.length(), builder.length());
        return builder.toString();
    }

}
