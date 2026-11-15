package io.github.jasoncai0.agent.permission;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative command classifier. This is a prompt for a human decision, not a
 * shell parser and not a security boundary.
 */
public final class CommandClassifier {
    private static final int MAX_SUMMARY = 500;
    private static final Pattern URL = Pattern.compile("https?://([^\\s'\"`]+)", Pattern.CASE_INSENSITIVE);
    private static final List<Pattern> HARD_DENY = List.of(
            Pattern.compile("(?i)(^|[;&|]\\s*)(sudo|su|doas)(\\s|$)"),
            Pattern.compile("(?i)\\brm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+(/|~|\\.)(\\s|$)"),
            Pattern.compile("(?i)(^|[;&|]\\s*)(mkfs|fdisk|parted)(\\s|$)"),
            Pattern.compile("(?i)\\bdd\\s+if=.*\\bof=/dev/"),
            Pattern.compile("(?i)(^|[;&|]\\s*)(shutdown|reboot|halt|poweroff)(\\s|$)"),
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{"),
            Pattern.compile("(?i)(^|[;&|]\\s*)(tee|>)\\s*/(etc|System|Library)/"),
            Pattern.compile("(?i)(^|[;&|]\\s*)(rm|chmod|chown|cp|mv)\\s+[^\\n]*(~/\\.ssh|/etc/|/System/|/Library/)")
    );
    private static final List<Pattern> DANGEROUS = List.of(
            Pattern.compile("(?i)\\brm\\s+-[a-z]*r[a-z]*f[a-z]*\\b"),
            Pattern.compile("(?i)\\bgit\\s+(reset\\s+--hard|clean\\s+-[a-z]*f[a-z]*|push\\s+--force)"),
            Pattern.compile("(?i)\\b(chmod|chown|kill|pkill|launchctl|crontab)\\b"),
            Pattern.compile("(?i)\\bdocker\\b"),
            Pattern.compile("(?i)(curl|wget)[^\\n]*\\|\\s*(bash|sh|zsh)"),
            Pattern.compile("(?i)(^|[;&|]\\s*)(bash|sh|zsh)\\s+-c\\b"),
            Pattern.compile("(?i)\\beval\\b|\\$\\(|`"),
            Pattern.compile("(?i)\\b(python|python3|node|perl)\\s+-[ce]\\b")
    );
    private static final List<Pattern> NETWORK = List.of(
            Pattern.compile("(?i)(^|[;&|]\\s*)(curl|wget)\\b"),
            Pattern.compile("(?i)\\bgit\\s+(clone|fetch|pull|push|ls-remote)\\b"),
            Pattern.compile("(?i)(^|[;&|]\\s*)(npm|pnpm|yarn|pip|pip3)\\b"),
            Pattern.compile("(?i)\\bgo\\s+(get|install|mod\\s+download)\\b"),
            Pattern.compile("(?i)(^|[;&|]\\s*)(ssh|scp|nc|ncat)\\b")
    );
    private static final List<Pattern> READ = List.of(
            Pattern.compile("(?i)^\\s*(ls|pwd|cat|head|tail|wc|grep|rg|find|file|stat|du|df)\\b"),
            Pattern.compile("(?i)^\\s*git\\s+(status|diff|log|show|branch|remote|rev-parse)\\b"),
            Pattern.compile("(?i)^\\s*(java|javac|mvn|gradle|node|npm|python3?|go|gofmt)\\s+(--?version|-version|version)\\b"),
            Pattern.compile("(?i)^\\s*(echo|printf|true|false|test)\\b")
    );
    private static final List<Pattern> WORKSPACE_WRITE = List.of(
            Pattern.compile("(?i)^\\s*(mkdir|touch|cp|mv|rm|ln)\\b"),
            Pattern.compile("(?i)^\\s*(mvn|gradle|gradlew|npm|pnpm|yarn|pytest|go|javac|python3?)\\b")
    );

    public CommandClassification classify(String command) {
        String raw = command == null ? "" : command.strip();
        String summary = oneLine(raw);
        if (raw.isEmpty()) {
            return CommandClassification.of(CommandClassification.Category.UNKNOWN, "(empty command)", null, 0);
        }
        for (Pattern pattern : HARD_DENY) {
            if (pattern.matcher(raw).find()) {
                return CommandClassification.hardDenied("Blocked by the hard-deny policy: " + pattern.pattern());
            }
        }
        // A pipe into a shell or an arbitrary interpreter is always dangerous,
        // even though it also contains a network command.
        for (Pattern pattern : DANGEROUS) {
            if (pattern.matcher(raw).find()) {
                return CommandClassification.of(CommandClassification.Category.DANGEROUS, summary, null, 0);
            }
        }
        if (raw.contains("$(") || raw.contains("`")) {
            return CommandClassification.of(CommandClassification.Category.UNKNOWN, summary, null, 0);
        }
        Matcher url = URL.matcher(raw);
        if (url.find()) {
            HostPort hostPort = hostPort(url.group(1));
            return CommandClassification.of(CommandClassification.Category.NETWORK, summary,
                    hostPort.host(), hostPort.port());
        }
        for (Pattern pattern : NETWORK) {
            if (pattern.matcher(raw).find()) {
                return CommandClassification.of(CommandClassification.Category.NETWORK, summary, null, 0);
            }
        }
        if (isReadOnly(raw)) {
            return CommandClassification.of(CommandClassification.Category.READ, summary, null, 0);
        }
        for (Pattern pattern : WORKSPACE_WRITE) {
            if (pattern.matcher(raw).find()) {
                return CommandClassification.of(CommandClassification.Category.WORKSPACE_WRITE, summary, null, 0);
            }
        }
        return CommandClassification.of(CommandClassification.Category.EXECUTE, summary, null, 0);
    }

    private static boolean isReadOnly(String raw) {
        for (String segment : raw.split("&&|\\|\\||;|\\|")) {
            String part = segment.strip();
            if (part.isEmpty()) {
                continue;
            }
            boolean matched = READ.stream().anyMatch(pattern -> pattern.matcher(part).find());
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static HostPort hostPort(String rawHost) {
        String host = rawHost;
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        int port = 0;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && colon < host.length() - 1 && host.indexOf(']') < 0) {
            try {
                port = Integer.parseInt(host.substring(colon + 1));
                host = host.substring(0, colon);
            } catch (NumberFormatException ignored) {
                // keep the full host
            }
        }
        return new HostPort(host.toLowerCase(Locale.ROOT), port);
    }

    private static String oneLine(String value) {
        String compact = value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").strip();
        return compact.length() <= MAX_SUMMARY ? compact : compact.substring(0, MAX_SUMMARY);
    }

    private record HostPort(String host, int port) {
    }
}
