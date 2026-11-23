package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class GlossaryLibraryTest {
    @Test
    public void importsNamedPacksAndRoundTripsTheOnlyActiveSelection() throws IOException {
        GlossaryLibrary.Pack gaming = pack("{\"name\":\"游戏\",\"terms\":[[\"cooldown\",\"冷却时间\"]]}");
        GlossaryLibrary.Pack software = pack("{\"name\":\"软件\",\"terms\":[[\"commit\",\"提交\"]]}");
        GlossaryLibrary library = GlossaryLibrary.EMPTY.addAndSelect(gaming).addAndSelect(software);
        GlossaryLibrary stored = GlossaryLibrary.parse(library.toJson());

        assertEquals("软件", stored.activeName());
        assertEquals(Collections.singletonMap("commit", "提交"), stored.activeTerms());
        assertEquals(2, stored.packs().size());
        assertEquals(Collections.singletonMap("cooldown", "冷却时间"),
                stored.select("游戏").activeTerms());
        assertEquals(2, stored.select(null).packs().size());
        assertTrue(stored.select(null).activeTerms().isEmpty());
        assertNull(stored.removeActive().activeName());
        assertEquals(1, stored.removeActive().packs().size());
        assertThrows(UnsupportedOperationException.class, () -> stored.activeTerms().clear());
    }

    @Test
    public void rejectsDuplicateSourcesNamesAndInvalidMemberShapes() throws IOException {
        GlossaryLibrary.Pack gaming = pack("{\"name\":\"Gaming\",\"terms\":[[\"cd\",\"冷却\"]]}");
        GlossaryLibrary library = GlossaryLibrary.EMPTY.addAndSelect(gaming);
        GlossaryLibrary.Pack duplicate = pack("{\"name\":\"gaming\",\"terms\":[[\"fps\",\"帧率\"]]}");
        assertThrows(IllegalArgumentException.class, () -> library.addAndSelect(duplicate));
        assertThrows(IllegalArgumentException.class, () -> pack(
                "{\"name\":\"Games\",\"terms\":[[\"CD\",\"冷却\"],[\"cd\",\"等待\"]]}"));
        assertThrows(IllegalArgumentException.class, () -> pack(
                "{\"name\":\"Games\",\"terms\":[[4,\"冷却\"]]}"));
        assertThrows(IllegalArgumentException.class, () -> pack(
                "{\"name\":\"Games\",\"terms\":[[\"cd\",\"line\\ntext\"]]}"));
        assertThrows(IllegalArgumentException.class, () -> pack(
                "{\"name\":\"Games\",\"terms\":[[\"cd\",\" \"]]}"));
        assertThrows(IllegalArgumentException.class, () -> pack(
                "{\"name\":\"Games\",\"terms\":[[\"cd\",\"冷却\"]]} true"));
    }

    @Test
    public void rejectsMalformedUtf8OversizedAndOverNestedDocuments() {
        byte[] malformed = {(byte) 0xc3, 0x28};
        assertThrows(CharacterCodingException.class, () -> GlossaryLibrary.readPack(
                new ByteArrayInputStream(malformed)));
        assertThrows(IllegalArgumentException.class, () -> GlossaryLibrary.readPack(
                new ByteArrayInputStream(new byte[GlossaryLibrary.MAX_PACK_BYTES + 1])));
        assertThrows(IllegalArgumentException.class, () -> pack(
                "{\"name\":\"Games\",\"terms\":[[[[[[[\"cd\"]]]]]]]}"));
    }

    @Test
    public void cacheVersionTracksActiveTermsWithoutExposingGlossaryContent() throws IOException {
        GlossaryLibrary.Pack gaming = pack("{\"name\":\"Gaming\",\"terms\":[[\"cd\",\"冷却\"]]}");
        GlossaryLibrary.Pack another = pack("{\"name\":\"Other\",\"terms\":[[\"fps\",\"帧率\"]]}");
        GlossaryLibrary.Pack equivalent = pack("{\"name\":\"Same\",\"terms\":[[\"cd\",\"冷却\"]]}");
        GlossaryLibrary library = GlossaryLibrary.EMPTY.addAndSelect(gaming).addAndSelect(another)
                .addAndSelect(equivalent);

        String gamingVersion = snapshot(library.select("Gaming")).version();
        assertEquals(gamingVersion, snapshot(library.select("Same")).version());
        assertNotEquals(gamingVersion, snapshot(library.select("Other")).version());
        assertNotEquals(gamingVersion, snapshot(library.select(null)).version());
        assertFalse(gamingVersion.contains("冷却"));
    }

    private static Config.Snapshot snapshot(GlossaryLibrary library) {
        return Config.read(new MemoryPrefs().put(Config.KEY_GLOSSARY_LIBRARY, library.toJson()));
    }

    private static GlossaryLibrary.Pack pack(String json) throws IOException {
        return GlossaryLibrary.readPack(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
