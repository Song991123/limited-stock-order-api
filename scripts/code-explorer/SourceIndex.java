import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Java 원문의 선언과 호출 위치를 JSON으로 추출. */
public class SourceIndex {
    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
        List<Path> files;
        try (var paths = Files.walk(root.resolve("src"))) {
            files = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK 21 is required, not a JRE");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none", "--release", "21", "-encoding", "UTF-8"), null,
                    manager.getJavaFileObjectsFromPaths(files));
            // 해설에는 구문 위치만 필요하므로 JDK parse만 호출. 타입 검사와 애플리케이션 실행은 생략.
            Iterable<? extends CompilationUnitTree> units = task.parse();
            Trees trees = Trees.instance(task);
            var result = new LinkedHashMap<String, Object>();
            for (var unit : units) {
                Path file = Path.of(unit.getSourceFile().toUri());
                String relative = root.relativize(file).toString().replace('\\', '/');
                var scanner = new IndexScanner(unit, trees.getSourcePositions());
                scanner.scan(unit, null);
                result.put(relative, Map.of("packageName", unit.getPackageName() == null ? "" : unit.getPackageName().toString(),
                        "symbols", scanner.symbols, "events", scanner.events));
            }
            var errors = diagnostics.getDiagnostics().stream().filter(item -> item.getKind() == Diagnostic.Kind.ERROR).toList();
            if (!errors.isEmpty()) throw new IllegalArgumentException(errors.toString());
            System.out.print(json(result));
        }
    }

    /** 선언과 실행 구문의 시작 위치와 끝 위치 기록. */
    private static final class IndexScanner extends TreePathScanner<Void, Void> {
        final CompilationUnitTree unit;
        final SourcePositions positions;
        final List<Map<String, Object>> symbols = new ArrayList<>();
        final List<Map<String, Object>> events = new ArrayList<>();
        final Deque<String> owners = new ArrayDeque<>();

        IndexScanner(CompilationUnitTree unit, SourcePositions positions) {
            this.unit = unit;
            this.positions = positions;
        }

        /** 끝 위치 바로 앞 문자로 끝 줄을 계산해 다음 줄이 구문 범위에 섞이는 문제 방지. */
        Map<String, Object> location(Tree tree, String kind, String name) {
            long start = positions.getStartPosition(unit, tree), end = positions.getEndPosition(unit, tree);
            if (start < 0 || end < start) return null;
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", kind); row.put("name", name);
            row.put("line", unit.getLineMap().getLineNumber(start));
            row.put("endLine", unit.getLineMap().getLineNumber(Math.max(start, end - 1)));
            row.put("column", unit.getLineMap().getColumnNumber(start));
            row.put("endColumn", unit.getLineMap().getColumnNumber(Math.max(start, end - 1)) + 1);
            row.put("owner", owners.isEmpty() ? "" : owners.peek());
            return row;
        }

        void event(Tree tree, String kind, String name) {
            var row = location(tree, kind, name);
            if (row != null) events.add(row);
        }

        @Override public Void visitClass(ClassTree tree, Void ignored) {
            var row = location(tree, "type", tree.getSimpleName().toString());
            if (row != null) { row.put("typeKind", tree.getKind().toString()); symbols.add(row); }
            owners.push(tree.getSimpleName().toString());
            super.visitClass(tree, ignored);
            owners.pop();
            return null;
        }

        @Override public Void visitMethod(MethodTree tree, Void ignored) {
            String name = tree.getName().contentEquals("<init>") ? owners.peek() : tree.getName().toString();
            var row = location(tree, "method", name);
            if (row != null) {
                row.put("parameters", tree.getParameters().stream().map(value -> value.getType() + " " + value.getName()).toList());
                row.put("returns", tree.getReturnType() == null ? "constructor" : tree.getReturnType().toString());
                symbols.add(row);
            }
            return super.visitMethod(tree, ignored);
        }

        @Override public Void visitVariable(VariableTree tree, Void ignored) {
            var row = location(tree, "variable", tree.getName().toString());
            if (row != null) { row.put("type", tree.getType() == null ? "var" : tree.getType().toString()); events.add(row); }
            return super.visitVariable(tree, ignored);
        }

        @Override public Void visitImport(ImportTree tree, Void ignored) {
            event(tree, "import", tree.getQualifiedIdentifier().toString());
            return super.visitImport(tree, ignored);
        }

        @Override public Void visitAnnotation(AnnotationTree tree, Void ignored) {
            var row = location(tree, "annotation", tree.getAnnotationType().toString());
            if (row != null) { row.put("arguments", tree.getArguments().stream().map(Object::toString).toList()); events.add(row); }
            return super.visitAnnotation(tree, ignored);
        }

        @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void ignored) {
            String name = tree.getMethodSelect() instanceof MemberSelectTree member ? member.getIdentifier().toString()
                    : tree.getMethodSelect().toString();
            var row = location(tree, "call", name);
            if (row != null) {
                row.put("arguments", tree.getArguments().stream().map(value -> shortText(value.toString())).toList());
                events.add(row);
            }
            return super.visitMethodInvocation(tree, ignored);
        }

        @Override public Void visitLiteral(LiteralTree tree, Void ignored) {
            var row = location(tree, "literal", tree.getKind().toString());
            if (row != null) { row.put("value", shortText(String.valueOf(tree.getValue()))); events.add(row); }
            return super.visitLiteral(tree, ignored);
        }

        @Override public Void visitIf(IfTree tree, Void ignored) {
            var row = location(tree, "if", shortText(tree.getCondition().toString()));
            if (row != null) events.add(row);
            return super.visitIf(tree, ignored);
        }
        @Override public Void visitReturn(ReturnTree tree, Void ignored) { event(tree, "return", shortText(String.valueOf(tree.getExpression()))); return super.visitReturn(tree, ignored); }
        @Override public Void visitThrow(ThrowTree tree, Void ignored) { event(tree, "throw", shortText(tree.getExpression().toString())); return super.visitThrow(tree, ignored); }
        @Override public Void visitTry(TryTree tree, Void ignored) { event(tree, "try", "try"); return super.visitTry(tree, ignored); }
        @Override public Void visitCatch(CatchTree tree, Void ignored) { event(tree, "catch", tree.getParameter().toString()); return super.visitCatch(tree, ignored); }
        @Override public Void visitSwitchExpression(SwitchExpressionTree tree, Void ignored) { event(tree, "switch", shortText(tree.getExpression().toString())); return super.visitSwitchExpression(tree, ignored); }
        @Override public Void visitSwitch(SwitchTree tree, Void ignored) { event(tree, "switch", shortText(tree.getExpression().toString())); return super.visitSwitch(tree, ignored); }
        @Override public Void visitCase(CaseTree tree, Void ignored) { event(tree, "case", shortText(tree.getLabels().toString())); return super.visitCase(tree, ignored); }
        @Override public Void visitForLoop(ForLoopTree tree, Void ignored) { event(tree, "loop", "for"); return super.visitForLoop(tree, ignored); }
        @Override public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void ignored) { event(tree, "loop", shortText(tree.getVariable().toString())); return super.visitEnhancedForLoop(tree, ignored); }
        @Override public Void visitWhileLoop(WhileLoopTree tree, Void ignored) { event(tree, "loop", shortText(tree.getCondition().toString())); return super.visitWhileLoop(tree, ignored); }
        @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void ignored) { event(tree, "lambda", shortText(tree.getParameters().toString())); return super.visitLambdaExpression(tree, ignored); }
        @Override public Void visitNewClass(NewClassTree tree, Void ignored) { event(tree, "new", tree.getIdentifier().toString()); return super.visitNewClass(tree, ignored); }
    }

    private static String shortText(String text) { return text.length() > 200 ? text.substring(0, 197) + "..." : text; }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream().map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue())).toList()) + "}";
        if (value instanceof Iterable<?> iterable) { var parts = new ArrayList<String>(); iterable.forEach(item -> parts.add(json(item))); return "[" + String.join(",", parts) + "]"; }
        var result = new StringBuilder("\"");
        for (char character : value.toString().toCharArray()) {
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> { if (character < 32) result.append(String.format("\\u%04x", (int) character)); else result.append(character); }
            }
        }
        return result.append('"').toString();
    }
}
