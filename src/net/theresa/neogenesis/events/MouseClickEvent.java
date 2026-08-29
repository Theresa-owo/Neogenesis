package net.theresa.neogenesis.events;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;

public class MouseClickEvent extends CancelableEvent<MouseClickEvent> {

    public final Button button;
    public final Type type;
    public final IBlockState block;
    public final Entity entity;

    private MouseClickEvent(Button button, Type type, IBlockState block, Entity entity) {
        this.button = button;
        this.type = type;
        this.block = block;
        this.entity = entity;
    }

    public static class Left extends MouseClickEvent {

        public Left(Type type, IBlockState block, Entity entity) {
            super(Button.LEFT, type, block, entity);
        }

    }

    public static class Middle extends MouseClickEvent {

        public Middle(Type type, IBlockState block, Entity entity) {
            super(Button.MIDDLE, type, block, entity);
        }

    }

    public static class Right extends MouseClickEvent {

        public Right(Type type, IBlockState block, Entity entity) {
            super(Button.RIGHT, type, block, entity);
        }

    }

    public enum Button {

        LEFT, MIDDLE, RIGHT

    }

    public enum Type {

        MISS, BLOCK, ENTITY

    }

}
