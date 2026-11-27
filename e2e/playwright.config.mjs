import { defineConfig } from '@playwright/test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

function detectCachedChromium() {
  if (process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH) {
    return process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;
  }
  const cacheRoot = path.join(os.homedir(), 'Library', 'Caches', 'ms-playwright');
  if (!fs.existsSync(cacheRoot)) return undefined;
  const dirs = fs.readdirSync(cacheRoot)
    .filter((d) => d.startsWith('chromium_headless_shell-'))
    .sort()
    .reverse();
  for (const dir of dirs) {
    for (const sub of ['chrome-headless-shell-mac-arm64', 'chrome-headless-shell-mac-x64']) {
      const bin = path.join(cacheRoot, dir, sub, 'chrome-headless-shell');
      if (fs.existsSync(bin)) return bin;
    }
  }
  return undefined;
}

const executablePath = process.env.CI ? undefined : detectCachedChromium();

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.mjs',
  timeout: 45_000,
  expect: {
    timeout: 10_000
  },
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    headless: true,
    viewport: { width: 1360, height: 860 },
    ...(executablePath ? { launchOptions: { executablePath } } : {})
  }
});
