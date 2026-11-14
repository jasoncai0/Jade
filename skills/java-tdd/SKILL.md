---
name: java-tdd
description: Drive a Java change with a failing check first, then the smallest implementation, then a real compile-and-run cycle.
tags: [java, testing, tdd, assertions]
triggers:
  - test
  - tdd
  - assertion
  - junit
  - verify
  - regression
---

# Test-first Java changes

Use this when the task asks for tests, correctness checks, or a change that must not regress.

## Procedure

1. Restate the behavior as one or two checks a machine can evaluate.
2. Add the smallest executable check before or alongside the implementation.
   - Fresh standalone workspace: a `main` method that calls the production code, checks values with `if`, prints `PASS`/`FAIL`, and exits with code 1 on failure.
   - Existing Maven project: JUnit 5 under `src/test/java`, executed with `mvn test`.
3. Run the check and confirm it fails for the expected reason when starting from a bug.
4. Implement the smallest change that makes the check pass. Do not rewrite unrelated code.
5. Compile and run the check again. A passing compile without running the check is not evidence.
6. If the check fails, use the exact assertion output and compiler line to make the next fix.
7. Finish with the command, the observed output, and the remaining limitations.

## Standalone assertion harness

```java
public class Main {
    public static void main(String[] args) {
        int actual = add(2, 3);
        if (actual != 5) {
            System.err.println("FAIL: expected 5 but got " + actual);
            System.exit(1);
        }
        System.out.println("PASS: add(2, 3) == 5");
    }

    static int add(int a, int b) {
        return a + b;
    }
}
```

Compile and run it. Exit code 0 plus `PASS` is the evidence; a printed claim alone is not.

## Maven projects

- Check `pom.xml` before adding dependencies.
- Run `mvn -q test`; a compile failure in test sources is still a failure.
- Do not add JUnit if the project has no test framework and the task only needs a tiny check.
- Keep tests deterministic: no sleeps, random values, external services, or wall-clock assertions.
- Test one behavior per test method; use clear assertion messages.

## Boundaries

- Do not weaken an assertion to make it pass.
- Do not delete the failing test.
- If the task is ambiguous, encode the most reasonable interpretation and state it.
