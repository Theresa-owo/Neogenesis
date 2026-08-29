package net.theresa.neogenesis.events;

import net.theresa.neogenesis.events.Event;

public class MovementUpdateEvent extends Event<MovementUpdateEvent> {

    public final double initStrafe;
    public final double initForward;
    public final boolean initJump;
    public final boolean initSneak;

    public double strafe;
    public double forward;
    public boolean jump;
    public boolean sneak;

    public MovementUpdateEvent(double strafe, double forward, boolean jump, boolean sneak) {
        this.initStrafe = strafe;
        this.initForward = forward;
        this.initJump = jump;
        this.initSneak = sneak;
        this.strafe = strafe;
        this.forward = forward;
        this.jump = jump;
        this.sneak = sneak;
    }

    public void setStrafe(double strafe) {
        this.strafe = strafe;
    }

    public void setForward(double forward) {
        this.forward = forward;
    }

    public void setJump(boolean jump) {
        this.jump = jump;
    }

    public void setSneak(boolean sneak) {
        this.sneak = sneak;
    }

}
