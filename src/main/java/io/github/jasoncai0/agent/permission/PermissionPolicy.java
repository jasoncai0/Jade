package io.github.jasoncai0.agent.permission;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PermissionPolicy {
    private static final Set<String> READ_TOOLS = Set.of(
            "read_file", "list_files", "search_text", "update_plan", "load_skill");
    private static final CommandClassifier CLASSIFIER = new CommandClassifier();

    private final PermissionMode mode;

    public PermissionPolicy(PermissionMode mode) {
        this.mode = mode == null ? PermissionMode.BALANCED : mode;
    }

    public PermissionMode mode() {
        return mode;
    }

    public PermissionEvaluation evaluate(String tool, Map<String, Object> args, PermissionSession session) {
        PermissionSession grants = session == null ? new PermissionSession() : session;
        if (READ_TOOLS.contains(tool)) {
            return PermissionEvaluation.allow(tool + " is read-only");
        }
        return switch (tool) {
            case "write_file", "edit_file" -> evaluateFile(tool, args, grants);
            case "compile_code", "compile_java" -> evaluateCompile(tool);
            case "run_code", "run_java" -> evaluateExecute(tool, args, grants);
            case "run_command" -> evaluateCommand(args, grants);
            default -> evaluateUnknown(tool);
        };
    }

    private PermissionEvaluation evaluateFile(String tool, Map<String, Object> args, PermissionSession grants) {
        String path = string(args.get("path"), ".");
        boolean sensitive = sensitivePath(path);
        String detail = "path=" + path + (tool.equals("write_file")
                ? " chars=" + string(args.get("content"), "").length()
                : " mode=" + (Boolean.TRUE.equals(args.get("replaceAll")) ? "replace-all" : "single"));
        if (mode == PermissionMode.READ_ONLY) {
            return PermissionEvaluation.deny("read-only mode blocks workspace writes");
        }
        if (sensitive) {
            return PermissionEvaluation.ask(PermissionKind.FILE_WRITE, "high",
                    "Write a sensitive path", detail, path, true, null, false);
        }
        if (grants.workspaceWrites() && mode != PermissionMode.STRICT) {
            return PermissionEvaluation.allow("workspace writes allowed for this session");
        }
        return switch (mode) {
            case BYPASS -> PermissionEvaluation.allow("permission checks bypassed");
            case STRICT -> PermissionEvaluation.ask(PermissionKind.FILE_WRITE, "medium",
                    "Write workspace file", detail, path, false, null, false);
            case READ_ONLY -> PermissionEvaluation.deny("read-only mode blocks workspace writes");
            default -> PermissionEvaluation.ask(PermissionKind.FILE_WRITE, "medium",
                    "Write workspace file", detail, path, false, null, false);
        };
    }

    private PermissionEvaluation evaluateCompile(String tool) {
        if (mode == PermissionMode.READ_ONLY) {
            return PermissionEvaluation.deny("read-only mode blocks compilation");
        }
        if (mode == PermissionMode.STRICT) {
            return PermissionEvaluation.ask(PermissionKind.EXECUTE, "low",
                    "Run the compiler", tool, "", false, null, false);
        }
        return PermissionEvaluation.allow("compilation is allowed");
    }

    private PermissionEvaluation evaluateExecute(String tool, Map<String, Object> args, PermissionSession grants) {
        if (mode == PermissionMode.READ_ONLY) {
            return PermissionEvaluation.deny("read-only mode blocks code execution");
        }
        if (grants.codeExecution() && mode != PermissionMode.STRICT) {
            return PermissionEvaluation.allow("code execution allowed for this session");
        }
        String detail = tool + " " + string(args.get("entry"), string(args.get("mainClass"), ""));
        return switch (mode) {
            case BYPASS -> PermissionEvaluation.allow("permission checks bypassed");
            case STRICT -> PermissionEvaluation.ask(PermissionKind.EXECUTE, "high",
                    "Run generated code", detail, "", false, null, false);
            case READ_ONLY -> PermissionEvaluation.deny("read-only mode blocks code execution");
            default -> PermissionEvaluation.ask(PermissionKind.EXECUTE, "high",
                    "Run generated code", detail, "", false, null, false);
        };
    }

    private PermissionEvaluation evaluateCommand(Map<String, Object> args, PermissionSession grants) {
        String command = string(args.get("command"), "");
        CommandClassification result = CLASSIFIER.classify(command);
        if (result.hardDenied()) {
            return PermissionEvaluation.deny(result.hardDenyReason());
        }
        if (mode == PermissionMode.BYPASS) {
            return PermissionEvaluation.allow("permission checks bypassed");
        }
        if (mode == PermissionMode.READ_ONLY) {
            return PermissionEvaluation.deny("read-only mode blocks command execution");
        }
        return switch (result.category()) {
            case READ -> PermissionEvaluation.allow("read-only command");
            case WORKSPACE_WRITE -> grants.workspaceWrites() && mode != PermissionMode.STRICT
                    ? PermissionEvaluation.allow("workspace writes allowed for this session")
                    : PermissionEvaluation.ask(PermissionKind.COMMAND, "medium",
                    "Workspace write command", result.summary(), "", false, null, false);
            case EXECUTE -> grants.codeExecution() && mode != PermissionMode.STRICT
                    ? PermissionEvaluation.allow("code execution allowed for this session")
                    : PermissionEvaluation.ask(PermissionKind.EXECUTE, "high",
                    "Execute command", result.summary(), "", false, null, false);
            case DANGEROUS -> PermissionEvaluation.ask(PermissionKind.COMMAND, "high",
                    "Dangerous command", result.summary(), "", false, null, false);
            case NETWORK -> {
                String host = result.host() == null || result.host().isBlank() ? "unknown host" : result.host();
                String target = result.port() > 0 ? host + ":" + result.port() : host;
                if (grants.allowsHost(host)) {
                    yield PermissionEvaluation.allowNetwork("network host allowed for this session", target);
                }
                yield PermissionEvaluation.ask(PermissionKind.NETWORK, "medium",
                        "Network access", result.summary(), target, false, host, true);
            }
            case UNKNOWN -> PermissionEvaluation.ask(PermissionKind.COMMAND, "high",
                    "Unclassified command", result.summary(), "", false, null, false);
        };
    }

    private PermissionEvaluation evaluateUnknown(String tool) {
        if (mode == PermissionMode.BYPASS) {
            return PermissionEvaluation.allow("permission checks bypassed");
        }
        if (mode == PermissionMode.READ_ONLY) {
            return PermissionEvaluation.deny("read-only mode blocks unknown tool");
        }
        return PermissionEvaluation.ask(PermissionKind.COMMAND, "medium",
                "Tool requires approval", tool, "", false, null, false);
    }

    public static boolean sensitivePath(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        String path = rawPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path.equals(".env") || path.startsWith(".env.")
                || path.equals(".git") || path.startsWith(".git/")
                || path.equals(".ssh") || path.startsWith(".ssh/")
                || path.equals("agents.md") || path.endsWith("/agents.md")
                || path.endsWith(".pem") || path.endsWith(".key")
                || path.contains("id_rsa") || path.contains("id_ed25519");
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
