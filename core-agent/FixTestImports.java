import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class FixTestImports {
    static final Path MAIN_ROOT = Paths.get("f:/ai/rag/core-agent/src/main/java/com/core/agent");
    static final Path TEST_ROOT = Paths.get("f:/ai/rag/core-agent/src/test/java/com/core/agent");
    static final Set<String> JAVA_LANG = Set.of(
        "Object", "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte",
        "Short", "Character", "Void", "Class", "Enum", "Exception", "RuntimeException",
        "Throwable", "Iterable", "Comparable", "Cloneable", "Serializable",
        "System", "StringBuilder", "StringBuffer", "Math", "Thread", "Runnable",
        "Process", "Runtime", "Package", "Module", "ClassLoader", "Number"
    );

    public static void main(String[] args) throws IOException {
        Map<String, Set<String>> classLocations = new HashMap<>();
        // 扫描 main 中的类定义
        try (var stream = Files.walk(MAIN_ROOT)) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".java")).toList()) {
                String content = Files.readString(p);
                Matcher pkgM = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE).matcher(content);
                if (!pkgM.find()) continue;
                String pkg = pkgM.group(1);
                Matcher clsM = Pattern.compile("\\b(public\\s+)?(class|interface|enum)\\s+(\\w+)").matcher(content);
                while (clsM.find()) {
                    classLocations.computeIfAbsent(clsM.group(3), k -> new HashSet<>()).add(pkg);
                }
            }
        }
        // 也扫描 test 中的类定义（测试类之间可能互相引用）
        try (var stream = Files.walk(TEST_ROOT)) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".java")).toList()) {
                String content = Files.readString(p);
                Matcher pkgM = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE).matcher(content);
                if (!pkgM.find()) continue;
                String pkg = pkgM.group(1);
                Matcher clsM = Pattern.compile("\\b(public\\s+)?(class|interface|enum)\\s+(\\w+)").matcher(content);
                while (clsM.find()) {
                    classLocations.computeIfAbsent(clsM.group(3), k -> new HashSet<>()).add(pkg);
                }
            }
        }

        // 修复 test import
        try (var stream = Files.walk(TEST_ROOT)) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".java")).toList()) {
                String content = Files.readString(p);
                Matcher pkgM = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE).matcher(content);
                if (!pkgM.find()) continue;
                String currentPkg = pkgM.group(1);

                content = content.replaceAll("import com\\.core\\.agent\\.[\\w.*]+;\\r?\\n", "");

                Set<String> refs = new HashSet<>();
                Matcher m = Pattern.compile("\\b[A-Z][a-zA-Z0-9_]*\\b").matcher(content);
                while (m.find()) refs.add(m.group());

                Set<String> imports = new TreeSet<>();
                for (String ref : refs) {
                    if (JAVA_LANG.contains(ref)) continue;
                    Set<String> pkgs = classLocations.get(ref);
                    if (pkgs == null) continue;
                    Set<String> external = new HashSet<>(pkgs);
                    external.remove(currentPkg);
                    if (external.isEmpty()) continue;
                    if (external.size() > 1) {
                        System.out.println("WARN ambiguous " + ref + " in " + p + ": " + external);
                        continue;
                    }
                    imports.add("import " + external.iterator().next() + "." + ref + ";");
                }

                if (!imports.isEmpty()) {
                    String importBlock = String.join("\n", imports) + "\n";
                    content = content.replaceFirst(
                        "^(package\\s+[\\w.]+;)\\n",
                        "$1\n" + importBlock
                    );
                    Files.writeString(p, content);
                }
            }
        }
        System.out.println("test imports fixed");
    }
}
