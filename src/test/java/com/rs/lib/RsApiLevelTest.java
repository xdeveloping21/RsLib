package com.rs.lib;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The manifest's RsLib-Api-Level comes from the pom; it must match {@link RsApi#LEVEL}. */
class RsApiLevelTest {

    @Test
    void thePomDeclaresTheSameApiLevel() throws IOException {
        Path pom = Path.of("pom.xml");
        assumeTrue(Files.isRegularFile(pom), "run from the project folder");
        String text = Files.readString(pom);
        assertTrue(text.contains("<rslib.api-level>" + RsApi.LEVEL + "</rslib.api-level>"),
                "pom.xml rslib.api-level differs from RsApi.LEVEL " + RsApi.LEVEL);
    }
}
