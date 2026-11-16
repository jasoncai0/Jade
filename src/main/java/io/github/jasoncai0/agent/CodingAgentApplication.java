package io.github.jasoncai0.agent;

import io.github.jasoncai0.agent.agent.AgentEvent;
import io.github.jasoncai0.agent.agent.AgentRun;
import io.github.jasoncai0.agent.agent.AgentService;
import io.github.jasoncai0.agent.agent.RunSnapshot;
import io.github.jasoncai0.agent.cli.CliOptions;
import io.github.jasoncai0.agent.cli.ConsoleRenderer;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.permission.PermissionDecision;
import io.github.jasoncai0.agent.permission.PermissionScope;
import io.github.jasoncai0.agent.question.QuestionMode;
import io.github.jasoncai0.agent.question.QuestionOption;
import io.github.jasoncai0.agent.question.QuestionRequest;
import io.github.jasoncai0.agent.config.ServerSettings;
import io.github.jasoncai0.agent.language.Language;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.DeepSeekClient;
import io.github.jasoncai0.agent.llm.LlmException;
import io.github.jasoncai0.agent.skill.Skill;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import io.github.jasoncai0.agent.web.WebServer;
import io.github.jasoncai0.agent.workspace.CommandRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class CodingAgentApplication {
    public static void main(String[] args) {
        int exit = new CodingAgentApplication().execute(args);
        if (exit >= 0) {
            System.exit(exit);
        }
    }

    private int execute(String[] args) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.println(CliOptions.usage());
            return 2;
        }
        if (options.help()) {
            System.out.println(CliOptions.usage());
            return 0;
        }
        if (options.version()) {
            System.out.println("Jade " + AppConfig.VERSION);
            return 0;
        }

        Log.setEnabled(options.mode() == CliOptions.Mode.SERVE);
        AppConfig config;
        try {
            config = applyOverrides(AppConfig.load(Path.of("").toAbsolutePath()), options);
        } catch (RuntimeException e) {
            System.err.println("Configuration error: " + e.getMessage());
            return 2;
        }

        return switch (options.mode()) {
            case CHECK -> check(config);
            case PING -> ping(config);
            case TASK -> headless(config, options);
            case SERVE -> serve(config);
        };
    }

    private int check(AppConfig config) {
        System.out.println("Jade " + AppConfig.VERSION + " - environment check");
        System.out.println("project root     " + config.projectRoot());
        System.out.println("skills dir       " + config.skillsDir());
        System.out.println("workspaces dir   " + config.workspacesDir());
        System.out.println("DeepSeek model   " + config.llm().model());
        System.out.println("DeepSeek base    " + config.llm().baseUrl());
        System.out.println("API key          " + (config.hasApiKey()
                ? "configured"
                : "MISSING (set DEEPSEEK_API_KEY in .env; real runs will fail)"));
        System.out.println("max turns        " + (config.agent().maxSteps() == 0 ? "unlimited" : config.agent().maxSteps())
                + " | token budget " + (config.agent().tokenBudget() == 0 ? "unlimited" : config.agent().tokenBudget()));
        System.out.println("auto verify      " + config.agent().autoVerify());
        System.out.println("sandbox          " + new CommandRunner().sandbox().description());

        try {
            SkillRegistry skills = SkillRegistry.load(config.skillsDir());
            System.out.println("skills           " + skills.all().size() + " "
                    + skills.all().stream().map(Skill::name).toList());
            ToolRegistry tools = BuiltInTools.registry();
            System.out.println("tools            " + tools.names().size() + " " + tools.names());
            System.out.println("languages        " + String.join(", ", Language.displayNames()));
            Files.createDirectories(config.workspacesDir());
            System.out.println("workspaces       writable=" + Files.isWritable(config.workspacesDir()));
        } catch (RuntimeException | IOException e) {
            System.err.println("Setup check failed: " + e.getMessage());
            return 1;
        }

        Path javac = Path.of(System.getProperty("java.home"), "bin", "javac");
        CommandRunner.CommandResult javacResult = new CommandRunner().run(
                List.of(javac.toString(), "-version"), config.projectRoot(), 10);
        System.out.println("javac            " + (javacResult.success()
                ? javacResult.combinedOutput(200).replace('\n', ' ').strip()
                : "NOT AVAILABLE at " + javac));
        CommandRunner.CommandResult maven = new CommandRunner().run(List.of("mvn", "-version"), config.projectRoot(), 15);
        System.out.println("maven            " + (maven.success()
                ? firstLine(maven.stdout())
                : "not found (optional; Maven projects need mvn on PATH)"));
        CommandRunner.CommandResult python = new CommandRunner().run(
                List.of("python3", "--version"), config.projectRoot(), 10);
        System.out.println("python3          " + (python.success()
                ? python.combinedOutput(200).replace('\n', ' ').strip()
                : "not found (Python workspaces need python3 on PATH)"));
        CommandRunner.CommandResult node = new CommandRunner().run(
                List.of("node", "--version"), config.projectRoot(), 10);
        System.out.println("node             " + (node.success()
                ? node.combinedOutput(200).replace('\n', ' ').strip()
                : "not found (JavaScript workspaces need node on PATH)"));
        CommandRunner.CommandResult go = new CommandRunner().run(
                List.of("go", "version"), config.projectRoot(), 10);
        System.out.println("go               " + (go.success()
                ? go.combinedOutput(200).replace('\n', ' ').strip()
                : "not found (Go workspaces need go on PATH)"));
        System.out.println();
        if (!javacResult.success()) {
            System.err.println("JDK compiler is required for the demo.");
            return 1;
        }
        System.out.println("Ready. Start the web console with: ./run.sh");
        return 0;
    }

    private int ping(AppConfig config) {
        if (!config.hasApiKey()) {
            System.err.println("DEEPSEEK_API_KEY is not configured. Copy .env.example to .env and set the key.");
            return 1;
        }
        System.out.println("Pinging " + config.llm().baseUrl() + " with model " + config.llm().model() + " ...");
        try {
            ChatResult result = new DeepSeekClient(config.llm()).chat(new ChatRequest(
                    List.of(ChatMessage.user("Reply with exactly: pong")), List.of(), 0.0, 16));
            System.out.println("DeepSeek replied: " + (result.content() == null ? "(empty)" : result.content().strip()));
            return 0;
        } catch (LlmException e) {
            System.err.println("Ping failed: " + e.getMessage());
            return 1;
        }
    }

    private int serve(AppConfig config) {
        AgentService agentService = null;
        WebServer webServer = null;
        try {
            agentService = new AgentService(config);
            webServer = new WebServer(agentService, config.server());
            webServer.start();
            System.out.println();
            System.out.println("Jade - Coding Agent " + AppConfig.VERSION);
            System.out.println("Web console  " + webServer.url());
            System.out.println("Model        " + config.llm().model());
            System.out.println("Skills       " + agentService.skills().all().size() + " | Tools "
                    + agentService.tools().names().size() + " | max turns "
                    + (config.agent().maxSteps() == 0 ? "unlimited" : config.agent().maxSteps())
                    + " | token budget " + (config.agent().tokenBudget() == 0 ? "unlimited" : config.agent().tokenBudget())
                    + " | auto-verify=" + config.agent().autoVerify());
            System.out.println("Workspaces   " + config.workspacesDir());
            System.out.println("API key      " + (config.hasApiKey() ? "configured" : "MISSING - set DEEPSEEK_API_KEY in .env"));
            System.out.println("Stop with    Ctrl+C");
            System.out.println();

            AgentService serviceRef = agentService;
            WebServer webRef = webServer;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                webRef.close();
                serviceRef.close();
            }, "agent-shutdown"));
            new CountDownLatch(1).await();
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        } catch (Exception e) {
            System.err.println("Cannot start the web console: " + e.getMessage());
            return 1;
        } finally {
            if (webServer != null) {
                webServer.close();
            }
            if (agentService != null) {
                agentService.close();
            }
        }
    }

    private int headless(AppConfig config, CliOptions options) {
        if (!config.hasApiKey()) {
            System.err.println("DEEPSEEK_API_KEY is not configured. Copy .env.example to .env and set the key.");
            return 1;
        }
        String task;
        try {
            task = options.task() != null ? options.task() : readTaskFile(options.taskFile());
        } catch (RuntimeException e) {
            System.err.println("Cannot read task: " + e.getMessage());
            return 2;
        }

        AgentService service = new AgentService(config);
        Thread hook = new Thread(service::close, "agent-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        ConsoleRenderer console = new ConsoleRenderer(System.out);
        AgentRun current = null;
        try {
            RunSnapshot started = service.start(task, options.workspace());
            AgentRun run = service.require(started.id());
            current = run;
            console.info("Jade " + AppConfig.VERSION);
            console.info("Run        " + started.id());
            console.info("Workspace  " + run.workspace().root());
            console.info("Model      " + config.llm().model() + " | max turns "
                + (config.agent().maxSteps() == 0 ? "unlimited" : config.agent().maxSteps())
                + " | token budget " + (config.agent().tokenBudget() == 0 ? "unlimited" : config.agent().tokenBudget()));
            console.info("Task       " + task);
            console.info("");

            long last = 0;
            while (true) {
                List<AgentEvent> events = run.events().after(last);
                if (events.isEmpty()) {
                    if (run.events().closed()) {
                        break;
                    }
                    AgentEvent next = run.events().awaitAfter(last, 1000);
                    if (next == null) {
                        continue;
                    }
                    events = List.of(next);
                }
                for (AgentEvent event : events) {
                    console.render(event);
                    if (event.type().equals("permission.request")) {
                        resolvePermission(service, run, event);
                    }
                    if (event.type().equals("question.request")) {
                        resolveQuestion(service, run);
                    }
                    last = event.seq();
                }
            }
            RunSnapshot finished = run.snapshot();
            console.finalSummary(finished);
            return "SUCCEEDED".equals(finished.status()) ? 0 : 1;
        } catch (RuntimeException e) {
            System.err.println("Run failed to start: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (current != null) {
                service.cancel(current.id());
            }
            return 130;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down
            }
            service.close();
        }
    }

    private void resolvePermission(AgentService service, AgentRun run, AgentEvent event) {
        Map<String, Object> data = event.data();
        String requestId = String.valueOf(data.getOrDefault("id", ""));
        String summary = String.valueOf(data.getOrDefault("summary", "Permission required"));
        String detail = String.valueOf(data.getOrDefault("detail", ""));
        System.out.println();
        System.out.println("[permission] " + summary);
        if (!detail.isBlank()) {
            System.out.println("  " + detail);
        }
        System.out.println("  [a]llow once  [s]ession  [d]eny");
        var console = System.console();
        if (console == null) {
            service.resolvePermission(run.id(), requestId, PermissionDecision.DENY,
                    PermissionScope.ONCE, "non-interactive stdin");
            System.out.println("  denied: no interactive terminal");
            return;
        }
        String answer = console.readLine("permission> ");
        String normalized = answer == null ? "d" : answer.strip().toLowerCase();
        PermissionDecision decision = normalized.startsWith("a") || normalized.startsWith("s")
                ? PermissionDecision.ALLOW : PermissionDecision.DENY;
        PermissionScope scope = normalized.startsWith("s") ? PermissionScope.SESSION : PermissionScope.ONCE;
        service.resolvePermission(run.id(), requestId, decision, scope, "cli");
    }


    private void resolveQuestion(AgentService service, AgentRun run) {
        QuestionRequest request = service.pendingQuestion(run.id());
        if (request == null) {
            return;
        }
        System.out.println();
        System.out.println("[decision] " + request.question());
        if (!request.detail().isBlank()) {
            System.out.println("  " + request.detail());
        }
        for (int i = 0; i < request.options().size(); i++) {
            QuestionOption option = request.options().get(i);
            System.out.println("  " + (i + 1) + ") " + option.label());
        }
        System.out.println(request.mode() == QuestionMode.TEXT
                ? "  type your answer" : "  choose option number"
                + (request.mode() == QuestionMode.MULTIPLE ? "s, comma separated" : ""));
        var console = System.console();
        if (console == null) {
            service.cancel(run.id());
            System.out.println("  cancelled: no interactive terminal");
            return;
        }
        if (request.mode() == QuestionMode.TEXT) {
            String answer = console.readLine("answer> ");
            if (answer == null) {
                service.cancel(run.id());
                return;
            }
            service.resolveQuestion(run.id(), request.id(), List.of(), answer);
            return;
        }
        String answer = console.readLine("choice> ");
        if (answer == null) {
            service.cancel(run.id());
            return;
        }
        List<String> selected = new ArrayList<>();
        for (String part : answer.split("[,\\s]+")) {
            if (part.isBlank()) continue;
            try {
                int index = Integer.parseInt(part.strip()) - 1;
                if (index >= 0 && index < request.options().size()) {
                    selected.add(request.options().get(index).id());
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (selected.isEmpty()) {
            System.out.println("  no valid choice; cancelling");
            service.cancel(run.id());
            return;
        }
        try {
            service.resolveQuestion(run.id(), request.id(), selected, "");
        } catch (IllegalArgumentException e) {
            System.out.println("  invalid choice: " + e.getMessage());
            service.cancel(run.id());
        }
    }

    private static String readTaskFile(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("--task-file is required");
        }
        try {
            Path path = Path.of(file).toAbsolutePath().normalize();
            if (Files.size(path) > 40_000) {
                throw new IllegalArgumentException("Task file is too large (max 40 KB)");
            }
            String text = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                throw new IllegalArgumentException("Task file is empty");
            }
            return text;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.lines().findFirst().orElse("");
    }

    private static AppConfig applyOverrides(AppConfig config, CliOptions options) {
        AgentSettings agent = config.agent();
        if (options.maxSteps() != null || options.permissionMode() != null) {
            agent = new AgentSettings(
                    options.maxSteps() == null ? agent.maxSteps() : options.maxSteps(),
                    agent.maxToolCallsPerStep(),
                    agent.contextCharBudget(),
                    agent.commandTimeoutSeconds(),
                    agent.autoVerify(),
                    options.permissionMode() == null ? agent.permissionMode() : options.permissionMode(),
                    agent.tokenBudget());
        }
        ServerSettings server = config.server();
        if (options.port() != null || options.host() != null) {
            server = new ServerSettings(
                    options.host() == null ? server.host() : options.host(),
                    options.port() == null ? server.port() : options.port());
        }
        return new AppConfig(config.projectRoot(), config.skillsDir(), config.workspacesDir(),
                config.llm(), agent, server);
    }
}
