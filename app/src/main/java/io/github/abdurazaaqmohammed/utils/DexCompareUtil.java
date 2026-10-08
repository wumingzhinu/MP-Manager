package io.github.abdurazaaqmohammed.utils;

import com.reandroid.apk.ApkModule;
import com.reandroid.dex.key.TypeKey;
import com.reandroid.dex.model.DexClass;
import com.reandroid.dex.model.DexDirectory;
import com.reandroid.dex.program.FieldProgram;
import com.reandroid.dex.program.MethodProgram;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.lang.reflect.Modifier;

/**
 * Class-level diff of two dex files, or of the dex files inside two APKs.
 *
 * <p>A class counts as modified when its smali differs, so a method whose body changed is caught
 * even though the class still declares the same fields and methods. The member-level view is a
 * drill-down on top of that: it reports signature-level differences, and separately flags when
 * only the instruction bodies moved.
 */
public final class DexCompareUtil {

    public enum Status { ADDED, REMOVED, MODIFIED }

    public static final class ClassDiff {
        public final String name;
        public final Status status;
        public final DexClass left;
        public final DexClass right;

        ClassDiff(String name, Status status, DexClass left, DexClass right) {
            this.name = name;
            this.status = status;
            this.left = left;
            this.right = right;
        }
    }

    /** One line of the member-level view. */
    public record MemberLine(String text, Status status) {
    }

    private DexCompareUtil() {
    }

    /**
     * A loaded dex view together with the archive that owns it, if any. Both halves are closed
     * together, so {@link #close()} is the only way to release them.
     */
    public static final class Opened implements AutoCloseable {
        private final DexDirectory directory;
        private final ApkModule module;

        Opened(DexDirectory directory, ApkModule module) {
            this.directory = directory;
            this.module = module;
        }

        public DexDirectory directory() {
            return directory;
        }

        @Override
        public void close() throws IOException {
            try {
                directory.close();
            } finally {
                if (module != null) {
                    module.close();
                }
            }
        }
    }

    /** Opens either a bare {@code .dex} or any archive holding {@code classes*.dex}. */
    public static Opened open(File file) throws IOException {
        if (isDexName(file.getName())) {
            DexDirectory directory = new DexDirectory();
            directory.addFile(file);
            directory.updateDexFileList();
            return new Opened(directory, null);
        }
        ApkModule module = ApkModule.loadApkFile(file);
        return new Opened(DexDirectory.fromZip(module.getZipEntryMap()), module);
    }

    public static boolean isDexName(String name) {
        if (name == null) return false;
        return name.toLowerCase(Locale.ROOT).endsWith(".dex");
    }

    /** True when the file is something {@link #open(File)} can read dex out of. */
    public static boolean isComparableName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return isDexName(lower)
                || lower.endsWith(".apk")
                || lower.endsWith(".jar")
                || lower.endsWith(".zip")
                || lower.endsWith(".apks");
    }

    public static List<ClassDiff> compare(DexDirectory left, DexDirectory right) throws IOException {
        Map<String, DexClass> a = classes(left);
        Map<String, DexClass> b = classes(right);
        List<ClassDiff> result = new ArrayList<>();
        for (Map.Entry<String, DexClass> entry : a.entrySet()) {
            DexClass other = b.get(entry.getKey());
            if (other == null) {
                result.add(new ClassDiff(entry.getKey(), Status.REMOVED, entry.getValue(), null));
            } else if (!smali(entry.getValue()).equals(smali(other))) {
                result.add(new ClassDiff(entry.getKey(), Status.MODIFIED, entry.getValue(), other));
            }
        }
        for (Map.Entry<String, DexClass> entry : b.entrySet()) {
            if (!a.containsKey(entry.getKey())) {
                result.add(new ClassDiff(entry.getKey(), Status.ADDED, null, entry.getValue()));
            }
        }
        result.sort((x, y) -> x.name.compareTo(y.name));
        return result;
    }

    /**
     * Member-level view of one modified class. Reports signature differences; when the signatures
     * all match but the smali still differs, the bodies are what moved.
     */
    public static List<MemberLine> members(DexClass left, DexClass right) throws IOException {
        List<MemberLine> lines = new ArrayList<>();
        if (left == null || right == null) return lines;

        String leftSuper = name(left.getSuperClassKey());
        String rightSuper = name(right.getSuperClassKey());
        if (!leftSuper.equals(rightSuper)) {
            lines.add(new MemberLine("extends " + leftSuper + "  ->  " + rightSuper, Status.MODIFIED));
        }

        Map<String, String> leftFields = fields(left);
        Map<String, String> rightFields = fields(right);
        for (Map.Entry<String, String> entry : leftFields.entrySet()) {
            String other = rightFields.get(entry.getKey());
            if (other == null) {
                lines.add(new MemberLine("field " + entry.getKey(), Status.REMOVED));
            } else if (!other.equals(entry.getValue())) {
                lines.add(new MemberLine("field " + entry.getKey() + "  " + entry.getValue()
                        + "  ->  " + other, Status.MODIFIED));
            }
        }
        for (String key : rightFields.keySet()) {
            if (!leftFields.containsKey(key)) {
                lines.add(new MemberLine("field " + key, Status.ADDED));
            }
        }

        Map<String, String> leftMethods = methods(left);
        Map<String, String> rightMethods = methods(right);
        for (Map.Entry<String, String> entry : leftMethods.entrySet()) {
            String other = rightMethods.get(entry.getKey());
            if (other == null) {
                lines.add(new MemberLine("method " + entry.getKey(), Status.REMOVED));
            } else if (!other.equals(entry.getValue())) {
                lines.add(new MemberLine("method " + entry.getKey() + "  " + entry.getValue()
                        + "  ->  " + other, Status.MODIFIED));
            }
        }
        for (String key : rightMethods.keySet()) {
            if (!leftMethods.containsKey(key)) {
                lines.add(new MemberLine("method " + key, Status.ADDED));
            }
        }

        if (lines.isEmpty()) {
            // Every signature matched, so only the instruction bodies can differ.
            lines.add(new MemberLine("bodies only (same signatures)", Status.MODIFIED));
        }
        return lines;
    }

    public static String smali(DexClass dexClass) throws IOException {
        return dexClass == null ? "" : dexClass.toSmali();
    }

    private static Map<String, DexClass> classes(DexDirectory directory) {
        Map<String, DexClass> map = new LinkedHashMap<>();
        Iterator<DexClass> iterator = directory.getDexClasses(key -> true);
        while (iterator.hasNext()) {
            DexClass dexClass = iterator.next();
            if (dexClass == null || dexClass.getKey() == null) continue;
            map.put(dexClass.getKey().getTypeName(), dexClass);
        }
        return map;
    }

    private static Map<String, String> fields(DexClass dexClass) {
        Map<String, String> map = new LinkedHashMap<>();
        Iterator<? extends FieldProgram> iterator = dexClass.declaredFields();
        while (iterator.hasNext()) {
            FieldProgram field = iterator.next();
            if (field == null || field.getKey() == null) continue;
            map.put(field.getKey().toString(), flags(field.getAccessFlagsValue()));
        }
        return map;
    }

    private static Map<String, String> methods(DexClass dexClass) {
        Map<String, String> map = new LinkedHashMap<>();
        Iterator<? extends MethodProgram> iterator = dexClass.declaredMethods();
        while (iterator.hasNext()) {
            MethodProgram method = iterator.next();
            if (method == null || method.getKey() == null) continue;
            // Registers are part of the fingerprint: an added local variable changes them.
            map.put(method.getKey().toString(), flags(method.getAccessFlagsValue())
                    + " regs=" + method.getRegistersCount());
        }
        return map;
    }

    /**
     * DEX and {@link Modifier} agree on the shared access bits
     * (public/private/protected/static/final/abstract/native/...), and Modifier.toString ignores
     * everything it does not know, so the raw value can be handed over as it is.
     */
    private static String flags(int accessFlags) {
        return Modifier.toString(accessFlags);
    }

    private static String name(TypeKey key) {
        return key == null ? "-" : key.getTypeName();
    }
}