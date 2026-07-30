package com.bowerbyte.blockyplanet.mixin;

import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes Screen's protected addDrawableChild method as public.
 * Uses erased types (Element) to avoid generic resolution issues.
 */
@Mixin(Screen.class)
public interface ScreenInvoker {

    @Invoker("addDrawableChild")
    Element invokeAddDrawableChild(Element drawable);
}
