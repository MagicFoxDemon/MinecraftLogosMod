package dev.logos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * What the mod's compiled classes refer to, for the tests that hold the code to its boundaries. Read from the class
 * files and not the sources: a name written in full in the middle of a method counts, a mention in a comment does not.
 */
final class CompiledClasses {
    /** The mod's own classes, as class files name them. */
    static final String MOD = "dev/logos/";

    private static final Path CLASSES = Path.of("build/classes/java/main");
    private static final Pattern IN_DESCRIPTOR = Pattern.compile("L([^;<]+)[;<]");

    private CompiledClasses() {}

    /**
     * Each of the mod's classes with every class it names: in its code, and in the types of its fields and methods.
     * A nested class counts as its outer class, on both sides.
     */
    static Map<String, Set<String>> references() throws IOException {
        Map<String, Set<String>> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(project().resolve(CLASSES).resolve(MOD))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                ClassReader reader = new ClassReader(Files.readAllBytes(file));
                named(reader, out.computeIfAbsent(outer(reader.getClassName()), k -> new TreeSet<>()));
            }
        }
        return out;
    }

    /** The package of one of the mod's classes below {@code dev.logos}, its first part only ({@code client} for {@code client/voice}); empty for {@code dev.logos} itself. */
    static String packageOf(String name) {
        String rest = name.substring(MOD.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? "" : rest.substring(0, slash);
    }

    /** The project directory, found upwards: unit tests run somewhere under the build directory. */
    static Path project() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(CLASSES).resolve(MOD))) return dir;
        }
        throw new IllegalStateException("no " + CLASSES + " above " + Path.of("").toAbsolutePath() + "; compile the mod first");
    }

    /** Java 21 has no class-file API: the constant pool is read with ASM (classes, name-and-types, method types). */
    private static void named(ClassReader reader, Set<String> out) {
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0) continue; // the second slot of a long or a double
            switch (reader.readByte(offset - 1)) {
                case 7 -> { // Class
                    String name = reader.readUTF8(offset, buffer);
                    if (name.startsWith("[")) inDescriptor(out, name);
                    else out.add(outer(name));
                }
                case 12 -> inDescriptor(out, reader.readUTF8(offset + 2, buffer)); // NameAndType
                case 16 -> inDescriptor(out, reader.readUTF8(offset, buffer)); // MethodType
                default -> {}
            }
        }
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                inDescriptor(out, descriptor);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                inDescriptor(out, descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static void inDescriptor(Set<String> out, String descriptor) {
        Matcher m = IN_DESCRIPTOR.matcher(descriptor);
        while (m.find()) out.add(outer(m.group(1)));
    }

    private static String outer(String name) {
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }
}
