package io.github.jasoncai0.agent.skill;

import java.util.List;
import java.util.Locale;

public record Skill(
        String name,
        String description,
        List<String> tags,
        List<String> triggers,
        String body,
        String relativePath
) {
    public Skill {
        tags = tags == null ? List.of() : List.copyOf(tags);
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
    }

    public String render() {
        return """
                ### Skill: %s
                %s

                %s
                """.formatted(name, description, body.strip());
    }

    public int score(String task) {
        if (task == null || task.isBlank()) {
            return 0;
        }
        String haystack = task.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String trigger : triggers) {
            if (!trigger.isBlank() && haystack.contains(trigger.toLowerCase(Locale.ROOT))) {
                score += 3;
            }
        }
        for (String tag : tags) {
            if (!tag.isBlank() && haystack.contains(tag.toLowerCase(Locale.ROOT))) {
                score += 2;
            }
        }
        if (haystack.contains(name.toLowerCase(Locale.ROOT))) {
            score += 4;
        }
        return score;
    }

    public String summary() {
        return name + " — " + description;
    }
}
