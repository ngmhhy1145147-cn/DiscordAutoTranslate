package io.github.wxmyyds.discordautotranslate.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** A bounded, immutable library of named translation glossaries. */
public final class GlossaryLibrary {
    public static final int MAX_PACKS = 8;
    public static final int MAX_TERMS = 128;
    public static final int MAX_TEXT_CHARACTERS = 80;
    public static final int MAX_PACK_BYTES = 24 * 1024;
    public static final int MAX_LIBRARY_BYTES = 64 * 1024;
    public static final GlossaryLibrary EMPTY = new GlossaryLibrary(Collections.emptyList(), null);

    private final List<Pack> packs;
    private final Pack active;
    private final String activeFingerprint;

    private GlossaryLibrary(List<Pack> packs, String activeName) {
        if (packs.size() > MAX_PACKS) {
            throw new IllegalArgumentException("Too many glossary packs");
        }
        this.packs = Collections.unmodifiableList(new ArrayList<>(packs));
        Set<String> names = new HashSet<>();
        Pack selected = null;
        for (Pack pack : packs) {
            if (!names.add(comparisonKey(pack.name))) {
                throw new IllegalArgumentException("Duplicate glossary pack name");
            }
            if (pack.name.equals(activeName)) {
                selected = pack;
            }
        }
        if (activeName != null && selected == null) {
            throw new IllegalArgumentException("Unknown active glossary pack");
        }
        active = selected;
        activeFingerprint = selected == null ? "" : Sha256.digestHex(selected.termsJson().toString(), "");
        requireSize(toJson(), MAX_LIBRARY_BYTES);
    }

    public static final class Pack {
        public final String name;
        public final Map<String, String> terms;

        private Pack(String name, Map<String, String> terms) {
            this.name = name;
            this.terms = Collections.unmodifiableMap(terms);
        }

        private JSONArray termsJson() {
            JSONArray entries = new JSONArray();
            for (Map.Entry<String, String> term : terms.entrySet()) {
                entries.put(new JSONArray().put(term.getKey()).put(term.getValue()));
            }
            return entries;
        }

        private JSONObject toJson() throws JSONException {
            return new JSONObject().put("name", name).put("terms", termsJson());
        }
    }

    public List<Pack> packs() {
        return packs;
    }

    /** The null selection keeps the packs available and disables glossary terms for requests. */
    public String activeName() {
        return active == null ? null : active.name;
    }

    public Map<String, String> activeTerms() {
        return active == null ? Collections.emptyMap() : active.terms;
    }

    String activeFingerprint() {
        return activeFingerprint;
    }

    public GlossaryLibrary addAndSelect(Pack pack) {
        List<Pack> updated = new ArrayList<>(packs);
        updated.add(pack);
        return new GlossaryLibrary(updated, pack.name);
    }

    public GlossaryLibrary select(String name) {
        return new GlossaryLibrary(packs, name);
    }

    public GlossaryLibrary removeActive() {
        List<Pack> updated = new ArrayList<>(packs);
        updated.remove(active);
        return new GlossaryLibrary(updated, null);
    }

    /** Read one UTF-8 JSON object using an input stream owned by the caller. */
    public static Pack readPack(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (count > MAX_PACK_BYTES - buffer.size()) {
                throw new IllegalArgumentException("Glossary pack is too large");
            }
            buffer.write(chunk, 0, count);
        }
        String json = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(buffer.toByteArray())).toString();
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') {
            json = json.substring(1);
        }
        return parsePack(readObject(json));
    }

    public static GlossaryLibrary parse(String json) {
        requireSize(json, MAX_LIBRARY_BYTES);
        JSONObject root = readObject(json);
        if (root.length() != 2 || !root.has("active") || !root.has("packs")) {
            throw new IllegalArgumentException("Invalid glossary library fields");
        }
        Object selection = root.opt("active");
        JSONArray entries = root.optJSONArray("packs");
        if (entries == null || entries.length() > MAX_PACKS
                || (selection != JSONObject.NULL && !(selection instanceof String))) {
            throw new IllegalArgumentException("Invalid glossary library");
        }
        List<Pack> packs = new ArrayList<>(entries.length());
        for (int i = 0; i < entries.length(); i++) {
            packs.add(parsePack(entries.optJSONObject(i)));
        }
        return new GlossaryLibrary(packs, selection == JSONObject.NULL ? null : (String) selection);
    }

    public String toJson() {
        try {
            JSONArray entries = new JSONArray();
            for (Pack pack : packs) {
                entries.put(pack.toJson());
            }
            return new JSONObject().put("active", active == null ? JSONObject.NULL : active.name)
                    .put("packs", entries).toString();
        } catch (JSONException e) {
            throw new IllegalStateException("Glossary serialization failed", e);
        }
    }

    private static JSONObject readObject(String json) {
        // Keep the platform's recursive JSON parser within the schema's depth bound.
        requireBoundedNesting(json);
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
                throw new IllegalArgumentException("Expected one glossary JSON object");
            }
            return (JSONObject) value;
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid glossary JSON");
        }
    }

    private static void requireBoundedNesting(String json) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == 0) {
                throw new IllegalArgumentException("Invalid glossary JSON character");
            }
            if (quoted && c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '{' || c == '[')) {
                if (++depth > 5) {
                    throw new IllegalArgumentException("Glossary JSON nesting is too deep");
                }
            } else if (!quoted && (c == '}' || c == ']')) {
                if (--depth < 0) {
                    throw new IllegalArgumentException("Invalid glossary JSON nesting");
                }
            } else if (!quoted && (c == '\'' || c == '/' || c == '#')) {
                throw new IllegalArgumentException("Invalid glossary JSON token");
            }
        }
        if (quoted || depth != 0) {
            throw new IllegalArgumentException("Incomplete glossary JSON");
        }
    }

    private static Pack parsePack(JSONObject object) {
        if (object == null || object.length() != 2 || !object.has("name") || !object.has("terms")) {
            throw new IllegalArgumentException("Invalid glossary pack fields");
        }
        String name = requireText(object.opt("name"));
        JSONArray entries = object.optJSONArray("terms");
        if (entries == null || entries.length() == 0 || entries.length() > MAX_TERMS) {
            throw new IllegalArgumentException("Invalid glossary term count");
        }
        Set<String> sources = new HashSet<>();
        Map<String, String> terms = new TreeMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONArray entry = entries.optJSONArray(i);
            if (entry == null || entry.length() != 2) {
                throw new IllegalArgumentException("Expected a source and target pair");
            }
            String source = requireText(entry.opt(0));
            String target = requireText(entry.opt(1));
            if (!sources.add(comparisonKey(source))) {
                throw new IllegalArgumentException("Duplicate source term");
            }
            terms.put(source, target);
        }
        return new Pack(name, terms);
    }

    private static String comparisonKey(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    private static String requireText(Object value) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Glossary values must be strings");
        }
        String text = (String) value;
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.SURROGATE || c == 0x2028 || c == 0x2029)) {
            throw new IllegalArgumentException("Glossary values must be single-line text");
        }
        String trimmed = text.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_TEXT_CHARACTERS
                || trimmed.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new IllegalArgumentException("Glossary text length is invalid");
        }
        return trimmed;
    }

    private static void requireSize(String json, int byteLimit) {
        if (json == null || json.length() > byteLimit
                || json.getBytes(StandardCharsets.UTF_8).length > byteLimit) {
            throw new IllegalArgumentException("Glossary JSON size is invalid");
        }
    }
}
