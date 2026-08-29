package net.theresa.neogenesis.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(value = ElementType.METHOD)
public @interface Subscribe {

    int priority() default Priority.NORMAL;

    boolean checkCanceled() default false;

}