---
name: javascript-cli
description: Build a standalone Node.js command-line program in a fresh workspace, then syntax-check and execute it with the real Node runtime before reporting success.
tags: [javascript, node, nodejs, cli]
triggers:
  - javascript
  - node
  - nodejs
  - .js
  - main.js
  - script
  - console.log
---

# Node.js CLI workflow

Use this when the task asks for a small JavaScript program that prints, computes, or exposes a command-line interface.

## Procedure

1. Inspect the workspace first. Do not assume `package.json` or installed npm dependencies exist.
2. Put the entry point in `main.js`, or `src/main.js` for a tiny multi-file project.
3. Prefer the Node standard library. Do not run `npm install` unless the task explicitly requires a package.
4. Write the source with `write_file`. Read it back when the change is more than a few lines.
5. Call `compile_code` with `language=javascript` (or omit it and let detection choose JavaScript). This runs `node --check`; any non-zero exit is a failure.
6. Call `run_code` with `language=javascript`, `entry=main.js`. Treat a non-zero exit as a failure.
7. Compare the actual stdout with the task requirement. If the output is wrong, edit and repeat.
8. Only after syntax-check and execution pass, give a short final answer that names the files and shows the observed output.

## Rules

- Use `process.argv.slice(2)` for arguments and `process.exitCode = 1` for a failed assertion.
- Keep the module runnable with plain `node main.js`; avoid a build step or TypeScript unless requested.
- Do not claim "it works" without a `compile_code` success and a `run_code` result.
- Keep the sample deterministic: no time, random, network, or environment-dependent output unless requested.
- If Node reports a syntax error, read the exact file and line; do not rewrite the whole program blindly.
- For ES modules, either use `.mjs` or set `"type": "module"` in `package.json` and verify with `node --check` before running.

## Minimal pattern

```javascript
function main() {
  console.log("hello from javascript");
}

main();
```

Run it with:

```bash
node main.js
```
