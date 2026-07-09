import { spawn, spawnSync } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";

const host = "127.0.0.1";
const port = "5173";
const baseUrl = `http://${host}:${port}`;
const args = process.argv.slice(2);

let server;

try {
  const alreadyRunning = await isAvailable(baseUrl);
  if (!alreadyRunning) {
    server = spawn(
      process.execPath,
      ["./node_modules/vite/bin/vite.js", "--host", host, "--port", port],
      {
        cwd: process.cwd(),
        env: { ...process.env, BROWSER: "none" },
        stdio: ["ignore", "pipe", "pipe"],
        windowsHide: true
      }
    );

    server.stdout.on("data", (chunk) => process.stdout.write(`[vite] ${chunk}`));
    server.stderr.on("data", (chunk) => process.stderr.write(`[vite] ${chunk}`));

    await waitForServer(baseUrl, server);
  }

  const exitCode = await runPlaywright(args);
  process.exitCode = exitCode;
} finally {
  await stopServer(server);
}

async function runPlaywright(playwrightArgs) {
  return await new Promise((resolve) => {
    const child = spawn(
      process.execPath,
      ["./node_modules/playwright/cli.js", "test", ...playwrightArgs],
      {
        cwd: process.cwd(),
        env: process.env,
        stdio: "inherit",
        windowsHide: true
      }
    );

    child.on("exit", (code, signal) => {
      if (signal) {
        resolve(1);
        return;
      }
      resolve(code ?? 1);
    });
  });
}

async function waitForServer(url, child) {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`Vite exited before ${url} became available.`);
    }
    if (await isAvailable(url)) {
      return;
    }
    await delay(250);
  }
  throw new Error(`Timed out waiting for ${url}.`);
}

async function isAvailable(url) {
  try {
    const response = await fetch(url);
    return response.ok;
  } catch {
    return false;
  }
}

async function stopServer(child) {
  if (!child || child.exitCode !== null) {
    return;
  }

  child.kill();
  const exited = await Promise.race([
    new Promise((resolve) => child.once("exit", () => resolve(true))),
    delay(2_000).then(() => false)
  ]);

  if (!exited && process.platform === "win32" && child.pid) {
    spawnSync("taskkill", ["/pid", String(child.pid), "/t", "/f"], {
      stdio: "ignore",
      windowsHide: true
    });
  }
}
