---
name: java-cli
description: Build a standalone Java command-line program in a fresh workspace, then compile and execute it with the real JDK before reporting success.
tags: [java, cli, javac, execution]
triggers:
  - cli
  - command line
  - main method
  - print
  - javac
  - run
---

# Java CLI workflow

Use this when the task asks for a small Java program that prints, computes, or exposes a command-line interface.

## Procedure

1. Inspect the workspace first. Do not assume a build file exists.
2. Choose a layout that a plain `javac` invocation can compile:
   - standalone: `src/Main.java` with `public class Main`
   - multiple sources: `src/*.java`, all in the default package, or a consistent package tree under `src/`
   - Maven only if the task explicitly requires dependencies or a `pom.xml` already exists
3. Make the entry point obvious: `public static void main(String[] args)`.
4. Write the source with `write_file`. Read it back when the change is more than a few lines.
5. Call `compile_java`. Treat any non-zero exit as a failure.
6. Call `run_java` with the fully qualified main class, for example `Main` or `com.example.Main`.
7. Compare the actual stdout with the task requirement. If the output is wrong, edit and repeat.
8. Only after compilation and execution pass, give a short final answer that names the files and shows the observed output.

## Rules

- Do not create a package just to look sophisticated. Match the requested scope.
- A public class must live in a file with the same name.
- Use `System.out.println` for output the task asks to see.
- Exit with a non-zero status on invalid input or failed checks.
- Do not claim "it works" without a `compile_java` success and a `run_java` result.
- Keep the sample deterministic: no time, random, locale, or network dependencies unless requested.
- If `javac` reports a line, open that exact file and line; do not rewrite the whole program blindly.

## Minimal pattern

```java
public class Main {
    public static void main(String[] args) {
        System.out.println("hello from java");
    }
}
```

Compile first, then run. The workspace's `.agent-build/classes` directory is the default output.
