package io.canvasmc.canvas.network;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.canvasmc.canvas.GlobalConfiguration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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
        .expireAfterAccess(5L, TimeUnit.MINUTES)
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

                Key key = new Key(value, value.getClass(), output.adventure$locale, output.registryAccess());
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

    private record Key(Component component, Class<?> type, @Nullable Locale locale, RegistryAccess registries) {
    }
}
