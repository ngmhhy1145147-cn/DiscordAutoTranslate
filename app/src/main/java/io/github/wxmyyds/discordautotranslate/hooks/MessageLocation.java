package io.github.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.TranslationScope;

import java.lang.reflect.Method;
import java.util.Objects;

/** Exact, profile-verified message location readers; no alternative access or string guessing. */
final class MessageLocation {
    final String guildId;
    final String channelId;

    MessageLocation(@Nullable String guildId, String channelId) {
        if (!TranslationScope.isValidId(channelId)
                || guildId != null && !TranslationScope.isValidId(guildId)) {
            throw new IllegalArgumentException("Invalid message location");
        }
        this.guildId = guildId;
        this.channelId = channelId;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MessageLocation)) return false;
        MessageLocation that = (MessageLocation) other;
        return Objects.equals(guildId, that.guildId) && channelId.equals(that.channelId);
    }

    @Override public int hashCode() {
        return 31 * Objects.hashCode(guildId) + channelId.hashCode();
    }

    static final class Reader {
        private final Method channel;
        private final Method guild;
        private final Method guildText;

        private Reader(Method channel, Method guild, Method guildText) {
            this.channel = channel;
            this.guild = guild;
            this.guildText = guildText;
        }

        static Reader create(HookConfiguration profile, Class<?> messageType) {
            try {
                String channelName = profile.accessorName("Message", "getChannelId");
                String guildName = profile.accessorName("Message", "getGuildId");
                String textName = profile.accessorName("GuildId", "asString");
                String guildType = profile.string("types", "GuildId");
                if (channelName == null || guildName == null || textName == null || guildType == null)
                    throw new IllegalStateException("Missing message location metadata");
                Method channel = messageType.getDeclaredMethod(channelName);
                Method guild = messageType.getDeclaredMethod(guildName);
                Class<?> wrapper = Class.forName(guildType, false, messageType.getClassLoader());
                Method text = wrapper.getDeclaredMethod(textName);
                if (channel.getReturnType() != long.class || guild.getReturnType() != wrapper
                        || text.getReturnType() != String.class
                        || java.lang.reflect.Modifier.isStatic(channel.getModifiers())
                        || java.lang.reflect.Modifier.isStatic(guild.getModifiers())
                        || java.lang.reflect.Modifier.isStatic(text.getModifiers())) {
                    throw new IllegalStateException("Invalid message location descriptor");
                }
                channel.setAccessible(true);
                guild.setAccessible(true);
                text.setAccessible(true);
                return new Reader(channel, guild, text);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Configured message location reader unavailable", error);
            }
        }

        MessageLocation read(Object message) {
            try {
                // A Kotlin non-null ChannelId is erased to long; unsigned rendering preserves its bits.
                long rawChannel = (Long) channel.invoke(message);
                Object rawGuild = guild.invoke(message);
                String guildId = rawGuild == null ? null : (String) guildText.invoke(rawGuild);
                return new MessageLocation(guildId, Long.toUnsignedString(rawChannel));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Message location capture failed", error);
            }
        }
    }
}
