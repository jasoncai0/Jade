---
name: go-cli
description: Build a standalone Go command-line program, compile it with the Go toolchain, and run it before reporting success.
tags: [go, golang, cli, build]
triggers: [go, golang, main.go, go build, go run, go test]
---

# Go CLI playbook

Use this when the task asks for Go code or a Go module.

## Workflow

1. Inspect the workspace first. Use the runtime that is already on `PATH`; do not download a Go SDK into the workspace.
2. Put the program in `main.go` (and additional package files beside it). If the task needs a module, create `go.mod` with a valid module path.
3. Check the compile gate:

   `compile_code` with `language=go`

   The verifier runs `go build ./...` against the real workspace.
4. Run the actual program:

   `run_code` with `language=go`, no entry (defaults to the package), and the needed `args`.

   The runner executes `go run .` in the workspace root.
5. If there are tests, run them with `run_command`: `go test ./...`. Do not claim a passing test you did not run.
6. Report the exact command, exit code, and observed stdout. Never claim success from reading the source alone.

## Rules

- Keep the package as `package main` with a `func main()` for CLI programs.
- Use only the Go standard library unless the task explicitly allows dependencies.
- `gofmt -l .` is not a build check. Run it on the project's Go files, not on a downloaded SDK tree.
- If compilation fails, read the first exact Go compiler error, fix the smallest cause, then run `compile_code` again.
