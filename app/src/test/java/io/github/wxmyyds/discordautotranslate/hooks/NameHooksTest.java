package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.core.TranslationScope;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

public class NameHooksTest {
    private static final String GUILD = "100";
    private static final String CHANNEL = "200";

    @Test
    public void targetSwitchesAndScopeAreIndependent() {
        TranslationScope scope = new TranslationScope(false,
                Collections.singletonList(new TranslationScope.Rule(GUILD, "", true)),
                Collections.singletonList(new TranslationScope.Rule("201", "", false)));
        Config.Snapshot config = snapshot(true, true, false, scope);

        assertTrue(NameHooks.eligible(config, NameHooks.Kind.SERVER, GUILD, CHANNEL, "Server"));
        assertFalse(NameHooks.eligible(config, NameHooks.Kind.CHANNEL, GUILD, CHANNEL, "Server"));
        assertFalse(NameHooks.eligible(config, NameHooks.Kind.SERVER, GUILD, "201", "Server"));
        assertFalse(NameHooks.eligible(config, NameHooks.Kind.SERVER, "101", CHANNEL, "Server"));
        assertFalse(NameHooks.eligible(config, NameHooks.Kind.SERVER, null, CHANNEL, "Server"));
        assertFalse(NameHooks.eligible(config, NameHooks.Kind.SERVER, GUILD, "0", "Server"));
        assertFalse(NameHooks.eligible(snapshot(false, true, false, scope),
                NameHooks.Kind.SERVER, GUILD, CHANNEL, "Server"));
    }

    @Test
    public void shortLabelsAreTranslatedButTargetScriptAndUntrustedShapesAreNot() {
        Config.Snapshot config = snapshot(true, false, true, TranslationScope.ALL);

        assertTrue(NameHooks.eligible(config, NameHooks.Kind.CHANNEL, GUILD, CHANNEL, "AI"));
        assertFalse(NameHooks.eligible(config, NameHooks.Kind.CHANNEL, GUILD, CHANNEL, "公告"));
        assertFalse(NameHooks.eligible(config, NameHooks.Kind.CHANNEL, GUILD, CHANNEL, "https://example.com"));
        assertFalse(NameHooks.eligible(config, NameHooks.Kind.CHANNEL, GUILD, CHANNEL, "two\nlines"));
        assertFalse(NameHooks.validLabel("two\u2028lines", 100));
        assertTrue(NameHooks.validLabel("😀", 1));
        assertEquals("18446744073709551615", NameHooks.snowflake(-1L));
        assertNull(NameHooks.snowflake(100));
    }

    @Test
    public void shippedNameBindingsRequireActualLocationAndDistinctViewIds() throws Exception {
        String raw = Files.readString(Path.of("src/main/assets/hook-config/352017.json"));
        HookConfiguration profile = HookConfiguration.parse(raw, 352017);
        assertEquals(Set.of("name.voiceOverlay", "name.voiceChannelSelector"), profile.nameHooks());
        JSONObject overlay = profile.hook("name.voiceOverlay");
        JSONObject selector = profile.hook("name.voiceChannelSelector");
        assertDescriptor(overlay, "com.discord.mobile_voice_overlay.views.OverlayMenuView",
                "setData", "ie.m");
        assertDescriptor(selector, "le.m", "f", "k3.k1", "int");
        assertEquals("c", overlay.getJSONArray("guildIdPath").getString(0));
        assertEquals("d", overlay.getJSONArray("guildIdPath").getString(1));
        assertEquals("w", selector.getJSONArray("modelPath").getString(0));

        JSONObject invalid = new JSONObject(raw);
        JSONObject binding = invalid.getJSONObject("hooks").getJSONObject("name.voiceOverlay");
        binding.put("serverViewId", binding.getInt("channelViewId"));
        assertThrows(JSONException.class, () -> HookConfiguration.parse(invalid.toString(), 352017));

        String older = Files.readString(Path.of("src/main/assets/hook-config/340013.json"));
        assertTrue(HookConfiguration.parse(older, 340013).nameHooks().isEmpty());
    }

    private static void assertDescriptor(JSONObject hook, String owner, String method,
            String... parameters) throws Exception {
        JSONObject target = hook.getJSONObject("target");
        assertEquals(owner, target.getString("class"));
        assertEquals(method, target.getString("name"));
        assertEquals("void", target.getString("returnType"));
        assertFalse(target.getBoolean("static"));
        assertEquals(parameters.length, target.getInt("parameterCount"));
        JSONObject actual = target.getJSONObject("parameterTypes");
        assertEquals(parameters.length, actual.length());
        for (int i = 0; i < parameters.length; i++) {
            assertEquals(parameters[i], actual.getString(Integer.toString(i)));
        }
    }

    private static Config.Snapshot snapshot(boolean enabled, boolean server, boolean channel,
            TranslationScope scope) {
        return Config.read(new MemoryPrefs()
                .put(Config.KEY_ENABLED, enabled)
                .put(Config.KEY_TRANSLATE_SERVER_NAMES, server)
                .put(Config.KEY_TRANSLATE_CHANNEL_NAMES, channel)
                .put(Config.KEY_API_URL, "https://example.com/v1")
                .put(Config.KEY_TARGET_LANG, "简体中文")
                .put(Config.KEY_TRANSLATION_SCOPE, scope.toJson()));
    }
}
