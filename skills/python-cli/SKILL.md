---
name: python-cli
description: Build a standalone Python 3 command-line program in a fresh workspace, then syntax-check and execute it with the real interpreter before reporting success.
tags: [python, python3, cli, execution]
triggers:
  - python
  - python3
  - .py
  - script
  - main.py
  - print
  - run
---

# Python CLI workflow

Use this when the task asks for a small Python program that prints, computes, or exposes a command-line interface.

## Procedure

1. Inspect the workspace first. Do not assume a virtualenv, package, or dependency exists.
2. Put the entry point in `main.py`, or `src/main.py` for a tiny multi-file project.
3. Prefer the standard library. Do not add `requirements.txt` or third-party packages unless the task asks for them.
4. Write the source with `write_file`. Read it back when the change is more than a few lines.
5. Call `compile_code` with `language=python` (or omit it and let detection choose Python). This runs `python3 -m py_compile`; any non-zero exit is a failure.
6. Call `run_code` with `language=python`, `entry=main.py`. Treat a non-zero exit as a failure.
7. Compare the actual stdout with the task requirement. If the output is wrong, edit and repeat.
8. Only after syntax-check and execution pass, give a short final answer that names the files and shows the observed output.

## Rules

- A Python module may contain code that executes at import time; keep `main.py` runnable directly.
- Use `if __name__ == "__main__":` when the file may be imported.
- Use `sys.argv` for arguments and `sys.exit(1)` for a failed assertion.
- Do not claim "it works" without a `compile_code` success and a `run_code` result.
- Keep the sample deterministic: no time, random, network, or environment-dependent output unless requested.
- If `python3` reports a traceback, read the exact file and line; do not rewrite the whole program blindly.

## Minimal pattern

```python
def main() -> None:
    print("hello from python")

if __name__ == "__main__":
    main()
```

Run it with:

```bash
python3 main.py
```
