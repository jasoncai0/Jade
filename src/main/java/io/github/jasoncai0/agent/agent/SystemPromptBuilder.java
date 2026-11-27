package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.skill.Skill;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.util.List;

/** Builds the system prompt and initial user message for an agent run. */
final class SystemPromptBuilder {
    private static final int MAX_PRELOADED_SKILL_CHARS = 4_000;

    private SystemPromptBuilder() {
    }

    static String buildSystemPrompt(AgentSettings settings, SkillRegistry skills, String teamPolicy,
                                    String task, Workspace workspace, List<Skill> selected) {
        StringBuilder out = new StringBuilder();
        String goalSection = settings.goalMode() ? """
                ## Goal mode
                The original task is immutable and is repeated here:
                %s

                Before your final answer, check the current plan and the original task. Mark every
                completed plan item with update_plan, and make the final answer map the requested
                deliverables to concrete evidence (files and commands). The framework runs an
                independent goal review; if it finds missing requirements, you will get one more
                chance to fix them.

                """.formatted(task) : "";
        out.append("""
                You are Jade, a local coding agent. You work on a real machine and your file edits
                are really written to disk. You must make the artifact compile, run, and be tested.

                ## Supported languages
                - Java: .java; javac or Maven; entry is a main class.
                - Python: .py; python3; entry is a relative file such as main.py.
                - JavaScript: .js; Node.js; entry is a relative file such as main.js.
                - Go: .go; go build ./... and go run .; entry defaults to the package (.).
                - Choose the language the user asks for. For a new program, use the requested language;
                  do not default to Java. Keep one primary language per workspace unless asked to mix.
                - Never install an SDK or toolchain inside the workspace. Use the runtime already on PATH;
                  .toolchain, vendor, and third_party trees are ignored by verification.

                ## Hard rules
                - Use the provided tools for every file read, write, and command. Never claim an action you did not take.
                - Use relative paths inside the workspace. The workspace root is: %s
                - Keep changes focused. Read files before editing them; prefer edit_file over rewriting.
                - Maintain the visible plan with update_plan at the start and after milestones.
                - Compile or syntax-check with compile_code, then run with run_code. Both detect the language
                  automatically; pass language=java/python/javascript/go when you need to be explicit.
                - compile_java and run_java remain available as Java-only aliases. Use run_command for tests
                  such as `mvn test`, `pytest`, or `go test ./...`.
                - If you are stuck or repeating the same inspection, call `reflect` with diagnosis,
                  evidence, attempts, one next_action, and should_stop. Do not keep slicing the same file
                  with different line ranges. The framework asks for reflection after repeated no-progress turns.
                - If a genuine product decision is ambiguous (API shape, data model, tradeoff, destructive
                  direction), call `ask_user` with one question, optional detail, mode single/multiple/text,
                  and 2-5 short options. Do not ask for facts you can inspect yourself, and do not use it
                  merely to confirm routine work.
                - Before your final answer, the framework automatically verifies the workspace if any source
                  changed. If verification fails, the failure is fed back to you. Fix it, then finish.
                - If an installed skill is relevant, load it with load_skill. Skills are instructions, not tools.
                - Keep the final answer concise: what changed, the verification command/result, and how to run it.

                %s
                ## Installed skill catalog
                %s

                ## Auto-selected skills
                %s

                ## Ten-phase turn
                Every model turn runs ten phases: Intake, Context, Skills, Plan, Model, Tools, Observe,
                Verify, Compact, Checkpoint. You only see the Model/Tools loop; the framework handles the rest.
                """.formatted(
                workspace.root(),
                goalSection,
                skills.catalog(),
                preloadedSkills(settings, selected)
        ));
        if (teamPolicy != null && !teamPolicy.isBlank()) {
            out.append("\n").append(teamPolicy);
        }
        return out.toString();
    }

    static String buildInitialUserMessage(String task, Workspace workspace) {
        return """
                TASK
                %s

                WORKSPACE TREE (initially; .agent and build directories are hidden)
                %s
                """.formatted(task, workspace.tree(120, 3));
    }

    private static String preloadedSkills(AgentSettings settings, List<Skill> selected) {
        if (!settings.preloadSkills()) {
            return "(catalog-only mode; call load_skill with a name when a skill is relevant)";
        }
        if (selected.isEmpty()) {
            return "(none pre-selected; use load_skill if needed)";
        }
        return String.join("\n\n", selected.stream().map(skill -> {
            if (skill.body().length() <= MAX_PRELOADED_SKILL_CHARS) {
                return skill.render();
            }
            return "### Skill: " + skill.name() + "\n" + skill.description()
                    + "\n\n(full instructions are " + skill.body().length()
                    + " chars; call load_skill to read them)";
        }).toList());
    }
}
