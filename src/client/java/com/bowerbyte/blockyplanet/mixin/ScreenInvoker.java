package com.bowerbyte.blockyplanet.mixin;

import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes Screen's protected addDrawableChild method as public
 * so we can add buttons to screens from event handlers.
 */
@Mixin(Screen.class)
public interface ScreenInvoker {

    @Invoker("addDrawableChild")
    <T extends Element & Drawable & Selectable> T invokeAddDrawableChild(T drawable);
}
