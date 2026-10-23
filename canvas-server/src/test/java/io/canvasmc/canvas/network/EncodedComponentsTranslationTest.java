package io.canvasmc.canvas.network;

import io.papermc.paper.adventure.AdventureComponent;
import java.text.MessageFormat;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encoded component cache leaves out what the server translates while encoding: a plugin component holding a
 * translation key is written fresh once the server has translations of its own, so a translation changed while the
 * server runs shows at once, and everything else stays cached.
 */
@Normal
@Tag("Network")
class EncodedComponentsTranslationTest {

    private static final String KEY = "canvas.test.greeting";

    @Test
    void plainPluginTextIsCached() {
        withTranslations(() -> assertFalse(EncodedComponents.translatedOnEncode(new AdventureComponent(Component.text("Hello")))));
    }

    @Test
    void translatableIsCachedWhileTheServerHasNoTranslations() {
        // Only the client translates the key then, so the bytes never change.
        assertFalse(EncodedComponents.translatedOnEncode(new AdventureComponent(Component.translatable(KEY))));
    }

    @Test
    void translatableIsWrittenFreshOnceTheServerTranslates() {
        withTranslations(() -> {
            assertTrue(EncodedComponents.translatedOnEncode(new AdventureComponent(Component.translatable(KEY))));
            assertTrue(EncodedComponents.translatedOnEncode(new AdventureComponent(Component.text("Hi ").append(Component.translatable(KEY)))));
            assertTrue(EncodedComponents.translatedOnEncode(new AdventureComponent(
                Component.text("Hover me").hoverEvent(HoverEvent.showText(Component.translatable(KEY)))
            )));
        });
    }

    @Test
    void vanillaComponentsAreLeftAlone() {
        withTranslations(() -> assertFalse(EncodedComponents.translatedOnEncode(net.minecraft.network.chat.Component.translatable(KEY))));
    }

    private static void withTranslations(final Runnable check) {
        TranslationStore.StringBased<MessageFormat> store = TranslationStore.messageFormat(Key.key("canvas", "encoded_components_test"));
        store.register(KEY, Locale.US, new MessageFormat("Hello"));
        GlobalTranslator.translator().addSource(store);
        try {
            check.run();
        } finally {
            GlobalTranslator.translator().removeSource(store);
        }
    }
}
