package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.workspace.CommandRunner;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The verification gate compiles the actual files in the workspace. It never trusts
 * the model's claim that code works.
 */
public final class Verifier {
    private final CommandRunner runner;

    public Verifier() {
        this(new CommandRunner());
    }

    public Verifier(CommandRunner runner) {
        this.runner = runner;
    }

    public VerificationResult verify(Workspace workspace, int timeoutSeconds) {
        List<Path> sources = workspace.javaSources();
        if (sources.isEmpty()) {
            return new VerificationResult(true, "No Java sources found; nothing to compile.", "", "(no Java sources)", 0);
        }
        if (Files.isRegularFile(workspace.root().resolve("pom.xml"))) {
            return verifyMaven(workspace, timeoutSeconds);
        }
        return verifyJavac(workspace, sources, timeoutSeconds);
    }

    public VerificationResult compile(Workspace workspace, int timeoutSeconds) {
        return verify(workspace, timeoutSeconds);
    }

    private VerificationResult verifyMaven(Workspace workspace, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        Path wrapper = workspace.root().resolve("mvnw");
        if (Files.isExecutable(wrapper)) {
            command.add("./mvnw");
        } else {
            command.add(System.getProperty("os.name", "").toLowerCase().contains("win") ? "mvn.cmd" : "mvn");
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

    public CommandRunner.CommandResult runMain(Workspace workspace, String mainClass, String args, int timeoutSeconds) {
        Path javac = javaTool("java");
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
        command.add(javac.toString());
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

    private static Path javaTool(String name) {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
        Path candidate = Path.of(System.getProperty("java.home"), "bin", executable);
        return Files.isExecutable(candidate) ? candidate : Path.of(executable);
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
