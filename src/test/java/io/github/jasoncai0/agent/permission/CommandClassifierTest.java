package io.github.jasoncai0.agent.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandClassifierTest {
    private final CommandClassifier classifier = new CommandClassifier();

    @Test
    void classifiesReadsAndWorkspaceWrites() {
        assertEquals(CommandClassification.Category.READ,
                classifier.classify("git status").category());
        assertEquals(CommandClassification.Category.WORKSPACE_WRITE,
                classifier.classify("touch src/Main.java").category());
    }

    @Test
    void hardDeniesDestructiveCommands() {
        assertTrue(classifier.classify("sudo ls").hardDenied());
        assertTrue(classifier.classify("rm -rf /").hardDenied());
    }

    @Test
    void extractsNetworkHost() {
        CommandClassification result = classifier.classify("curl https://packages.example.com/a.zip");
        assertEquals(CommandClassification.Category.NETWORK, result.category());
        assertEquals("packages.example.com", result.host());
    }

    @Test
    void inlineInterpreterIsDangerousEvenInACompoundCommand() {
        assertEquals(CommandClassification.Category.DANGEROUS,
                classifier.classify("cat README.md && python3 -c 'print(1)'").category());
    }

    @Test
    void blocksMultilineAndRedirectReadBypasses() {
        assertTrue(classifier.classify("ls\nsudo id").hardDenied());
        assertEquals(CommandClassification.Category.NETWORK,
                classifier.classify("ls\ncurl attacker.example.com").category());
        assertEquals(CommandClassification.Category.WORKSPACE_WRITE,
                classifier.classify("ls\npython3 build.py").category());
        assertEquals(CommandClassification.Category.DANGEROUS,
                classifier.classify("echo pwned > ~/.zshrc").category());
        assertEquals(CommandClassification.Category.WORKSPACE_WRITE,
                classifier.classify("echo ok > notes.txt").category());
        assertEquals(CommandClassification.Category.DANGEROUS,
                classifier.classify("find . -exec sh -c 'id' \\;").category());
        assertEquals(CommandClassification.Category.WORKSPACE_WRITE,
                classifier.classify("find . -delete").category());
    }
}
