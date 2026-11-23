package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Every added profile is parsed by production code; this cannot verify host signatures. */
public class HookProfileInventoryTest {
    @Test
    public void bothShippedBodyRenderersDeclarePrivateShadowGetter() throws Exception {
        for (int version : new int[]{340013, 352017}) {
            String json = Files.readString(Path.of("src/main/assets/hook-config/" + version + ".json"));
            HookConfiguration config = HookConfiguration.parse(json, version);
            JSONObject render = config.hook("message.render");
            assertNotNull("Body render metadata missing for " + version, render);
            assertTrue("Shadow getter must be an explicit string for " + version,
                    render.get("shadowViewGetter") instanceof String);
            assertEquals("Private native shadow getter for " + version, "getShadowView",
                    render.getString("shadowViewGetter"));
        }
    }

    @Test
    public void everyShippedProfileMatchesItsFilenameAndDeclaresInstalledHooks() throws Exception {
        Set<String> baselineHooks = Set.of("message.bind", "message.render", "message.textConversion",
                "embed.bind", "poll.bind", "poll.answerBind", "poll.textSet", "poll.accessibilitySet");
        Map<Integer, Set<String>> nameHooksByVersion = Map.of(
                352017, Set.of("name.voiceOverlay", "name.voiceChannelSelector"));
        List<Path> paths;
        try (Stream<Path> files = Files.list(Path.of("src/main/assets/hook-config"))) {
            paths = files.sorted().collect(Collectors.toList());
        }
        assertFalse("No shipped hook profiles", paths.isEmpty());
        for (Path path : paths) {
            String filename = path.getFileName().toString();
            assertTrue("Only exact numeric version files: " + filename,
                    filename.matches("[1-9][0-9]*\\.json"));
            int version = Integer.parseInt(filename.substring(0, filename.length() - 5));
            String json = Files.readString(path);
            HookConfiguration config = HookConfiguration.parse(json, version);
            assertEquals(version, config.versionCode());
            JSONObject root = new JSONObject(json);
            JSONObject hooks = root.getJSONObject("hooks");
            Set<String> configured = new HashSet<>();
            hooks.keys().forEachRemaining(configured::add);
            Set<String> nameHooks = nameHooksByVersion.getOrDefault(version, Set.of());
            Set<String> expected = new HashSet<>(baselineHooks);
            expected.addAll(nameHooks);
            assertEquals("Installed hooks for " + version, expected, configured);
            assertEquals("Enabled label hooks for " + version, nameHooks, config.nameHooks());
            for (String id : configured) {
                JSONObject target = hooks.getJSONObject(id).getJSONObject("target");
                String context = version + " " + id;
                assertTrue(context, target.get("name") instanceof String);
                assertFalse(context, target.getString("name").isEmpty());
                assertFalse(context, target.has("namePrefix"));
                assertFalse(context, target.has("minParameterCount"));
                assertFalse(context, target.has("selection"));
                assertTrue(context, target.get("parameterCount") instanceof Integer);
                int count = target.getInt("parameterCount");
                assertTrue(context, count >= 0);
                assertTrue(context, target.get("static") instanceof Boolean);
                JSONObject types = target.optJSONObject("parameterTypes");
                if (types != null) {
                    Iterator<String> keys = types.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        int index = Integer.parseInt(key);
                        assertEquals(context, Integer.toString(index), key);
                        assertTrue(context, index >= 0 && index < count);
                        assertTrue(context, types.get(key) instanceof String);
                        String type = types.getString(key);
                        assertFalse(context, type.isEmpty());
                        assertFalse(context, type.contains("*"));
                    }
                }
            }
            JSONArray required = root.getJSONArray("requiredHooks");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < required.length(); i++) {
                String id = required.getString(i);
                assertTrue("Duplicate required hook: " + id, ids.add(id));
                assertNotNull(config.hook(id));
                assertTrue(config.isEnabled(id));
            }
            assertEquals(baselineHooks, ids);
            assertTrue(config.hasAllRequiredHooks(baselineHooks));
            Set<String> incomplete = new HashSet<>(expected);
            incomplete.remove("message.bind");
            assertFalse(config.hasAllRequiredHooks(incomplete));
        }
    }
}
