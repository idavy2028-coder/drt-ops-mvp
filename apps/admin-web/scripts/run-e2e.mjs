import { spawn, spawnSync } from "node:child_process";
import { createServer } from "node:net";
import { setTimeout as delay } from "node:timers/promises";

const host = "127.0.0.1";
const port = await availablePort(host);
const baseUrl = `http://${host}:${port}`;
const args = process.argv.slice(2);

let server;

try {
  server = spawn(
    process.execPath,
    ["./node_modules/vite/bin/vite.js", "--host", host, "--port", String(port), "--strictPort"],
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

  const exitCode = await runPlaywright(args, baseUrl);
  process.exitCode = exitCode;
} finally {
  await stopServer(server);
}

async function runPlaywright(playwrightArgs, url) {
  return await new Promise((resolve) => {
    const child = spawn(
      process.execPath,
      ["./node_modules/playwright/cli.js", "test", ...playwrightArgs],
      {
        cwd: process.cwd(),
        env: { ...process.env, PLAYWRIGHT_BASE_URL: url },
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

async function availablePort(address) {
  return await new Promise((resolve, reject) => {
    const reservation = createServer();
    reservation.once("error", reject);
    reservation.listen(0, address, () => {
      const boundAddress = reservation.address();
      const selectedPort = typeof boundAddress === "object" && boundAddress !== null ? boundAddress.port : undefined;
      reservation.close((error) => {
        if (error) {
          reject(error);
          return;
        }
        if (selectedPort === undefined) {
          reject(new Error("Unable to reserve a local port for Playwright."));
          return;
        }
        resolve(selectedPort);
      });
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
