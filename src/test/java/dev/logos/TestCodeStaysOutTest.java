package dev.logos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The released jar leaves out {@code gametest} and {@code client/uitest}, so a mention of them anywhere else
 * compiles and runs in development and only fails on a player's machine.
 */
class TestCodeStaysOutTest {
    private static final Path RELATIVE = Path.of("src/main/java/dev/logos");
    private static final Pattern MENTION = Pattern.compile("dev\\.logos\\.(gametest|client\\.uitest)\\b");

    @Test
    void mainCodeDoesNotReferToTestCode() throws IOException {
        Path sources = sources();
        Path[] testCode = {sources.resolve("gametest"), sources.resolve("client/uitest")};
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(sources)) {
            offenders = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> Stream.of(testCode).noneMatch(f::startsWith))
                    .filter(TestCodeStaysOutTest::mentionsTestCode)
                    .toList();
        }
        assertEquals(List.of(), offenders, "these would crash in the released jar, which leaves out test code");
    }

    /** The source tree, found upwards: unit tests run somewhere under the build directory. */
    private static Path sources() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(RELATIVE))) return dir.resolve(RELATIVE);
        }
        throw new IllegalStateException("no " + RELATIVE + " above " + Path.of("").toAbsolutePath());
    }

    private static boolean mentionsTestCode(Path file) {
        try {
            return MENTION.matcher(Files.readString(file)).find();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
