package com.favasur.blockyplanet.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor mixin that exposes Screen's protected addRenderableWidget method
 * so the Create World screen hook can add widgets from outside the Screen hierarchy.
 */
@Mixin(Screen.class)
public interface ScreenAccessor {

    @Invoker("addRenderableWidget")
    <T extends GuiEventListener> T invokeAddRenderableWidget(T widget);
}
