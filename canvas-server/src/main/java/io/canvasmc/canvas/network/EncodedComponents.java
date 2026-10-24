package io.canvasmc.canvas.network;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.canvasmc.canvas.GlobalConfiguration;
import io.papermc.paper.adventure.AdventureComponent;
import io.papermc.paper.adventure.PaperAdventure;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the bytes a text component encodes to on the network, so a name, lore line or sidebar
 * line sent again is copied instead of encoded again. Every component is written by turning it
 * into NBT through its codec first, which is most of what the network threads allocate when
 * menus, items with lore and named entities are sent to many players.
 * <p>
 * Components are compared by what they say, so an item a menu builds anew each update still
 * finds the bytes of the one before it. The language of the player and whether the component
 * came from a plugin are part of the key, since both change what is written. Switched with
 * {@code networking.cache-encoded-components} and {@code /canvas reload}; switching it off
 * drops everything kept.
 * <p>
 * A plugin component holding a translation key is translated for the player's language while it
 * is encoded, through the server's translations, which a plugin may change while the server runs.
 * Such a component is never cached while the server has translations of its own, so a changed
 * translation shows at once; plain text and keys only the client translates are cached as before.
 */
public final class EncodedComponents {

    // Bigger ones are rare and mostly chat that is never sent the same twice.
    private static final int MAX_BYTES = 16384;
    private static final long MAX_WEIGHT = 64L * 1024L * 1024L;
    // The component a key holds weighs about twice the bytes it encodes to.
    private static final int WEIGHT_PER_BYTE = 3;

    private static final Cache<Key, byte[]> ENCODED = CacheBuilder.newBuilder()
        .maximumWeight(MAX_WEIGHT)
        .weigher((Key key, byte[] bytes) -> bytes.length * WEIGHT_PER_BYTE)
        .expireAfterAccess(Duration.ofMinutes(5L))
        .concurrencyLevel(16)
        .build();

    // Whether a plugin component holds a translation key anywhere in it. Adventure components
    // cannot change, so the answer is kept per instance, weakly and by identity.
    private static final Cache<net.kyori.adventure.text.Component, Boolean> TRANSLATABLE = CacheBuilder.newBuilder()
        .weakKeys()
        .maximumSize(8192)
        .concurrencyLevel(16)
        .build();

    // The hash of a plugin component, kept per instance, weakly and by identity. Hashing the
    // wrapper turns the whole component into a vanilla one first, every time; the component it
    // wraps cannot change, so its hash is worked out once, and one sent to many players is
    // hashed once for all of them.
    private static final Cache<net.kyori.adventure.text.Component, Integer> HASHES = CacheBuilder.newBuilder()
        .weakKeys()
        .maximumSize(16384)
        .concurrencyLevel(16)
        .build();

    private EncodedComponents() {
    }

    /**
     * @param codec the codec that encodes a component
     * @return the same codec, writing the bytes it wrote before for a component it saw before
     */
    public static StreamCodec<RegistryFriendlyByteBuf, Component> cached(final StreamCodec<RegistryFriendlyByteBuf, Component> codec) {
        return new StreamCodec<>() {
            @Override
            public Component decode(final RegistryFriendlyByteBuf input) {
                return codec.decode(input);
            }

            @Override
            public void encode(final RegistryFriendlyByteBuf output, final Component value) {
                GlobalConfiguration configuration = GlobalConfiguration.getInstance();
                if (configuration == null || !configuration.networking.cacheEncodedComponents) {
                    if (ENCODED.size() > 0L) {
                        ENCODED.invalidateAll();
                    }

                    codec.encode(output, value);
                    return;
                }

                // Translated for the player while it is encoded: the server's translations decide
                // the bytes, and they may change, so it is written fresh every time.
                if (EncodedComponents.translatedOnEncode(value)) {
                    codec.encode(output, value);
                    return;
                }

                Key key = EncodedComponents.key(value, output.adventure$locale, output.registryAccess());
                byte[] bytes = ENCODED.getIfPresent(key);
                if (bytes != null) {
                    output.writeBytes(bytes);
                    return;
                }

                int start = output.writerIndex();
                codec.encode(output, value);
                int length = output.writerIndex() - start;
                if (length <= MAX_BYTES) {
                    byte[] written = new byte[length];
                    output.getBytes(start, written);
                    ENCODED.put(key, written);
                }
            }
        };
    }

    /**
     * @param value a component about to be encoded
     * @return whether the server's own translations may change what it encodes to
     */
    static boolean translatedOnEncode(final Component value) {
        if (!(value instanceof AdventureComponent wrapper)) {
            return false;
        }

        net.kyori.adventure.text.Component adventure = wrapper.adventure$component();
        Boolean known = TRANSLATABLE.getIfPresent(adventure);
        if (known == null) {
            known = EncodedComponents.holdsTranslatable(adventure);
            TRANSLATABLE.put(adventure, known);
        }

        return known && PaperAdventure.hasAnyTranslations();
    }

    // Walks the component, its children and its hover text.
    private static boolean holdsTranslatable(final net.kyori.adventure.text.Component component) {
        if (component instanceof TranslatableComponent) {
            return true;
        }

        HoverEvent<?> hover = component.style().hoverEvent();
        if (hover != null && hover.value() instanceof net.kyori.adventure.text.Component text && EncodedComponents.holdsTranslatable(text)) {
            return true;
        }

        for (net.kyori.adventure.text.Component child : component.children()) {
            if (EncodedComponents.holdsTranslatable(child)) {
                return true;
            }
        }

        return false;
    }

    /**
     * A plugin component is keyed by the component it wraps, which compares and hashes without
     * being converted; a vanilla one by itself, since it may still be changed and its hash is
     * not safe to keep.
     *
     * @param value a component about to be encoded
     * @param locale the language of the player it goes to
     * @param registries the registries it is encoded with
     * @return the key its bytes are kept under
     */
    static Key key(final Component value, final @Nullable Locale locale, final RegistryAccess registries) {
        if (!(value instanceof AdventureComponent wrapper)) {
            return new Key(value, value.getClass(), locale, registries, value.hashCode());
        }

        net.kyori.adventure.text.Component adventure = wrapper.adventure$component();
        Integer hash = HASHES.getIfPresent(adventure);
        if (hash == null) {
            hash = adventure.hashCode();
            HASHES.put(adventure, hash);
        }

        return new Key(adventure, value.getClass(), locale, registries, hash);
    }

    record Key(Object component, Class<?> type, @Nullable Locale locale, RegistryAccess registries, int hash) {

        @Override
        public int hashCode() {
            int result = this.hash;
            result = 31 * result + this.type.hashCode();
            result = 31 * result + Objects.hashCode(this.locale);
            return 31 * result + System.identityHashCode(this.registries);
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Key key
                && this.hash == key.hash
                && this.type == key.type
                && this.registries == key.registries
                && Objects.equals(this.locale, key.locale)
                && this.component.equals(key.component);
        }
    }
}
