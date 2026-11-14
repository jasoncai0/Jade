package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.language.Language;
import io.github.jasoncai0.agent.workspace.CommandRunner;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The verification gate checks the actual files in the workspace with the real
 * toolchain for the detected language. It never trusts the model's claim that
 * code works.
 */
public final class Verifier {
    private static final int MAX_SOURCE_FILES = 200;

    private final CommandRunner runner;

    public Verifier() {
        this(new CommandRunner());
    }

    public Verifier(CommandRunner runner) {
        this.runner = runner;
    }

    /**
     * Detects the primary language from source files. Ties use the declaration
     * order in {@link Language}: Java, Python, JavaScript.
     */
    public Language detect(Workspace workspace) {
        return Language.detect(workspace.sourceFiles(".java", ".py", ".js"));
    }

    /**
     * Verifies all sources using the detected language. This is the automatic
     * gate used before a run may finish.
     */
    public VerificationResult verify(Workspace workspace, int timeoutSeconds) {
        Language language = detect(workspace);
        if (language == null) {
            return new VerificationResult(true,
                    "No supported source files found; nothing to compile.",
                    "", "(no .java, .py, or .js sources)", 0);
        }
        return verify(workspace, language, timeoutSeconds);
    }

    public VerificationResult verify(Workspace workspace, Language language, int timeoutSeconds) {
        if (language == null) {
            return verify(workspace, timeoutSeconds);
        }
        return switch (language) {
            case JAVA -> verifyJava(workspace, timeoutSeconds);
            case PYTHON -> verifyPython(workspace, timeoutSeconds);
            case JAVASCRIPT -> verifyJavaScript(workspace, timeoutSeconds);
        };
    }

    public VerificationResult compile(Workspace workspace, int timeoutSeconds) {
        return verify(workspace, timeoutSeconds);
    }

    private VerificationResult verifyJava(Workspace workspace, int timeoutSeconds) {
        List<Path> sources = workspace.sourceFiles(".java");
        if (sources.isEmpty()) {
            return new VerificationResult(true, "No Java sources found; nothing to compile.",
                    "", "(no Java sources)", 0);
        }
        if (Files.isRegularFile(workspace.root().resolve("pom.xml"))) {
            return verifyMaven(workspace, timeoutSeconds);
        }
        return verifyJavac(workspace, sources, timeoutSeconds);
    }

    private VerificationResult verifyPython(Workspace workspace, int timeoutSeconds) {
        List<Path> sources = workspace.sourceFiles(".py");
        if (sources.isEmpty()) {
            return new VerificationResult(true, "No Python sources found; nothing to compile.",
                    "", "(no Python sources)", 0);
        }
        if (sources.size() > MAX_SOURCE_FILES) {
            return new VerificationResult(false,
                    "Too many Python source files to verify: " + sources.size()
                            + " (maximum " + MAX_SOURCE_FILES + ").",
                    "", "(not run)", 0);
        }
        String python = pythonExecutable();
        List<String> command = new ArrayList<>();
        command.add(python);
        command.add("-m");
        command.add("py_compile");
        for (Path source : sources) {
            command.add(workspace.relativize(source));
        }
        return runVerification(workspace, command, timeoutSeconds,
                "Python syntax check passed (" + sources.size() + " file(s)).",
                "python3 -m py_compile failed.");
    }

    private VerificationResult verifyJavaScript(Workspace workspace, int timeoutSeconds) {
        List<Path> sources = workspace.sourceFiles(".js");
        if (sources.isEmpty()) {
            return new VerificationResult(true, "No JavaScript sources found; nothing to compile.",
                    "", "(no JavaScript sources)", 0);
        }
        String node = nodeExecutable();
        StringBuilder output = new StringBuilder();
        long durationMs = 0;
        for (Path source : sources) {
            List<String> command = new ArrayList<>();
            command.add(node);
            command.add("--check");
            command.add(workspace.relativize(source));
            try {
                CommandRunner.CommandResult result = runner.run(command, workspace.root(), timeoutSeconds);
                durationMs += result.durationMs();
                output.append("$ ").append(result.commandLine()).append('\n')
                        .append(result.combinedOutput(18_000)).append('\n');
                if (!result.success()) {
                    return new VerificationResult(false, "node --check failed.",
                            result.commandLine(), output.toString(), durationMs);
                }
            } catch (CommandRunner.CommandRejectedException e) {
                return new VerificationResult(false, "Cannot run node: " + e.getMessage(),
                        String.join(" ", command), output + e.getMessage(), durationMs);
            }
        }
        return new VerificationResult(true,
                "JavaScript syntax check passed (" + sources.size() + " file(s)).",
                "node --check " + sources.size() + " file(s)", output.toString(), durationMs);
    }

    private VerificationResult verifyMaven(Workspace workspace, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        Path wrapper = workspace.root().resolve("mvnw");
        if (Files.isExecutable(wrapper)) {
            command.add("./mvnw");
        } else {
            command.add(isWindows() ? "mvn.cmd" : "mvn");
        }
        command.add("-q");
        command.add("-DskipTests");
        command.add("compile");
        try {
            CommandRunner.CommandResult result = runner.run(command, workspace.root(), timeoutSeconds);
            String output = result.combinedOutput(18_000);
            return new VerificationResult(result.success(),
                    result.success() ? "Maven compile passed." : "Maven compile failed.",
                    result.commandLine(), output, result.durationMs());
        } catch (CommandRunner.CommandRejectedException e) {
            return new VerificationResult(false, "Cannot run Maven: " + e.getMessage(),
                    String.join(" ", command), e.getMessage(), 0);
        }
    }

    private VerificationResult verifyJavac(Workspace workspace, List<Path> sources, int timeoutSeconds) {
        Path classes = workspace.classesDir();
        clean(classes);
        Path javac = javaTool("javac");
        List<String> command = new ArrayList<>();
        command.add(javac.toString());
        command.add("-encoding");
        command.add("UTF-8");
        command.add("-d");
        command.add(workspace.relativize(classes));
        for (Path source : sources) {
            command.add(workspace.relativize(source));
        }
        try {
            CommandRunner.CommandResult result = runner.run(command, workspace.root(), timeoutSeconds);
            String output = result.combinedOutput(18_000);
            return new VerificationResult(result.success(),
                    result.success()
                            ? "javac compiled " + sources.size() + " source file(s)."
                            : "javac failed.",
                    result.commandLine(), output, result.durationMs());
        } catch (CommandRunner.CommandRejectedException e) {
            return new VerificationResult(false, "Cannot run javac: " + e.getMessage(),
                    String.join(" ", command), e.getMessage(), 0);
        }
    }

    private VerificationResult runVerification(Workspace workspace, List<String> command,
                                               int timeoutSeconds, String successSummary,
                                               String failureSummary) {
        try {
            CommandRunner.CommandResult result = runner.run(command, workspace.root(), timeoutSeconds);
            String output = result.combinedOutput(18_000);
            return new VerificationResult(result.success(),
                    result.success() ? successSummary : failureSummary,
                    result.commandLine(), output, result.durationMs());
        } catch (CommandRunner.CommandRejectedException e) {
            return new VerificationResult(false, "Cannot run " + command.get(0) + ": " + e.getMessage(),
                    String.join(" ", command), e.getMessage(), 0);
        }
    }

    /**
     * Runs the language's entry point. Java expects a class name; Python and
     * JavaScript expect a workspace-relative source file.
     */
    public CommandRunner.CommandResult run(Workspace workspace, Language language,
                                           String entry, String args, int timeoutSeconds) {
        if (language == Language.JAVA) {
            String mainClass = entry == null || entry.isBlank() ? "Main" : entry.strip();
            return runMain(workspace, mainClass, args, timeoutSeconds);
        }
        String file = entry == null || entry.isBlank() ? language.defaultEntry(workspace) : entry.strip();
        if (file == null) {
            throw new IllegalArgumentException("No " + language.displayName() + " entry point found.");
        }
        Path resolved = workspace.resolve(file);
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("Entry file does not exist: " + file);
        }
        String relative = workspace.relativize(resolved);
        List<String> command = new ArrayList<>();
        command.add(language == Language.PYTHON ? pythonExecutable() : nodeExecutable());
        command.add(relative);
        if (args != null && !args.isBlank()) {
            command.addAll(List.of(args.strip().split("\\s+")));
        }
        return runner.run(command, workspace.root(), timeoutSeconds);
    }

    public CommandRunner.CommandResult runMain(Workspace workspace, String mainClass, String args, int timeoutSeconds) {
        Path javaExe = javaTool("java");
        List<String> classpath = new ArrayList<>();
        Path classes = workspace.root().resolve(".agent-build/classes");
        if (Files.isDirectory(classes)) {
            classpath.add(workspace.relativize(classes));
        }
        Path targetClasses = workspace.root().resolve("target/classes");
        if (Files.isDirectory(targetClasses)) {
            classpath.add(workspace.relativize(targetClasses));
        }
        if (classpath.isEmpty()) {
            classpath.add(workspace.relativize(workspace.classesDir()));
        }
        List<String> command = new ArrayList<>();
        command.add(javaExe.toString());
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator, classpath));
        command.add(mainClass);
        if (args != null && !args.isBlank()) {
            for (String arg : args.strip().split("\\s+")) {
                command.add(arg);
            }
        }
        return runner.run(command, workspace.root(), timeoutSeconds);
    }

    private static String pythonExecutable() {
        String configured = System.getenv("PYTHON");
        return configured == null || configured.isBlank() ? "python3" : configured.strip();
    }

    private static String nodeExecutable() {
        String configured = System.getenv("NODE");
        return configured == null || configured.isBlank() ? "node" : configured.strip();
    }

    private static Path javaTool(String name) {
        String executable = isWindows() ? name + ".exe" : name;
        Path candidate = Path.of(System.getProperty("java.home"), "bin", executable);
        return Files.isExecutable(candidate) ? candidate : Path.of(executable);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void clean(Path classes) {
        try (Stream<Path> walk = Files.walk(classes)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(classes))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // A stale class is a warning, not a reason to fail verification.
                        }
                    });
        } catch (IOException ignored) {
            // best effort
        }
    }

    public record VerificationResult(
            boolean ok,
            String summary,
            String command,
            String output,
            long durationMs
    ) {
        public Map<String, Object> toEventData() {
            return Map.of(
                    "ok", ok,
                    "summary", summary,
                    "command", command,
                    "output", output,
                    "durationMs", durationMs
            );
        }
    }
}
