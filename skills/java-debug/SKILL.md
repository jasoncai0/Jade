---
name: java-debug
description: Diagnose a Java compile or runtime failure from the real compiler output, make one minimal fix, and re-run until the workspace passes.
tags: [java, debugging, compiler, stacktrace]
triggers:
  - compile error
  - exception
  - stack trace
  - debug
  - cannot find symbol
  - fix
  - failure
---

# Debugging a Java workspace

Use this when compilation or execution fails, or when the task is to fix an existing Java bug.

## Procedure

1. Read the exact failure. Capture the compiler line, the exception class, and the first stack frame that points at workspace source.
2. Open the referenced file around the reported line with `read_file`. Do not guess from the file name.
3. Form one hypothesis that explains the whole error.
4. Change the smallest number of lines that tests the hypothesis.
5. Recompile. If compilation passes, run the entry point or test and inspect the actual output.
6. If the failure moved, repeat from step 1 with the new error. Do not stack speculative fixes.
7. Report the failing command, the fixed lines, and the passing command.

## Compile errors

- `cannot find symbol`: check spelling, imports, package, and whether a required source file exists.
- `incompatible types` / `possible lossy conversion`: check the declared type and cast only when the value range is known.
- `class X is public, should be declared in a file named X.java`: rename the file; do not duplicate the class.
- `reached end of file while parsing`: look for a missing brace before the reported location.
- `package does not exist`: confirm the dependency or source tree; do not invent an import.

## Runtime errors

- `NullPointerException`: find which reference is null and why; guard at the boundary that owns the invariant.
- `ArrayIndexOutOfBoundsException`: check the loop bound and the array length.
- `NumberFormatException`: validate the input text before parsing and handle the failure path.
- `ClassNotFoundException` / `NoClassDefFoundError`: check the classpath and the exact compiled class name.
- `UnsupportedClassVersionError`: compile and run with the same JDK.

## Rules

- Never claim success based on reading code. Run the real command.
- Do not suppress exceptions with empty `catch` blocks.
- Do not disable a failing test or lower a requirement to get green output.
- Keep unrelated style changes out of the fix.
- If the error is environmental (JDK missing, no network), say so and stop rather than editing source.

## Useful commands

```text
compile_java
run_java mainClass=Main
run_command command="mvn -q test"
```

The workspace is isolated under `agent-workspaces/run-*`; all paths in tool calls are relative to it.
