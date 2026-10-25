package dev.logos.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;

import dev.logos.LogosMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Lets an input method (Chinese, Japanese …) type into LDLib text fields. Since 26.1 the game turns
 * the IME off every tick unless a text input has said it holds the focus, which only vanilla's own
 * edit boxes do; LDLib's fields never say so, so pinyin never reached them. This says it for them,
 * and draws the text being composed beside the field the way vanilla's boxes do.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
final class LdlibIme {
    /** The LDLib field the IME is on for, if any. */
    private static UIElement input;
    private static IMEPreeditOverlay composing;

    private LdlibIme() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        UIElement focused = mc.screen instanceof ModularUIScreen screen ? screen.modularUI.getFocusedElement() : null;
        UIElement now = focused != null && focused.isTextInput() ? focused : null;
        if (now == input) return;
        composing = null;
        if (input != null) {
            if (mc.screen != null) mc.onTextInputFocusChange(mc.screen, false);
            else mc.textInputManager().stopTextInput();
        }
        input = now;
        if (now != null) mc.onTextInputFocusChange(mc.screen, true);
    }

    @SubscribeEvent
    static void onPreedit(ScreenEvent.Preedit.Pre event) {
        if (input == null || !(event.getScreen() instanceof ModularUIScreen)) return;
        var text = event.getPreeditEvent();
        composing = text == null ? null : new IMEPreeditOverlay(text, Minecraft.getInstance().font, 9 + 1);
    }

    /** Places the candidate window at the field, and the text being composed just under it. */
    @SubscribeEvent
    static void onRender(ScreenEvent.Render.Post event) {
        if (input == null || !(event.getScreen() instanceof ModularUIScreen)) return;
        int x = Math.round(input.getPositionX()) + 2, y = Math.round(input.getPositionY());
        int w = Math.round(input.getSizeWidth()), h = Math.round(input.getSizeHeight());
        if (composing != null) {
            composing.updateInputPosition(x, y + Math.max(0, (h - 10) / 2));
            event.getGuiGraphics().setPreeditOverlay(composing);
        } else {
            Minecraft.getInstance().textInputManager().setTextInputArea(x, y, x + w, y + h);
        }
    }
}
