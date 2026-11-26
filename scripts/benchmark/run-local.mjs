import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { once } from "node:events";
import fs from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { measure } from "./measure.mjs";
import { study } from "./study.mjs";

const [mysqlDirectory, javaExecutable, jarFile, scale, mode = "baseline"] =
  process.argv.slice(2);
const size = Number(scale);
assert.ok(
  process.argv.length <= 7 &&
    [10000, 100000].includes(size) &&
    ["baseline", "study", "pagination"].includes(mode),
  "Usage: node scripts/benchmark/run-local.mjs <mysql bin> <java.exe> <built application jar> <10000|100000> [study|pagination]",
);
const mysqlBin = await fs.realpath(mysqlDirectory);
const java = await fs.realpath(javaExecutable);
const jar = await fs.realpath(jarFile);
const repo = await fs.realpath(process.cwd());
const target = await fs.realpath(path.join(repo, "target"));
assert.equal(target, path.join(repo, "target"));
const run = await fs.mkdtemp(path.join(target, "m443-runtime-"));
assert.equal(path.dirname(await fs.realpath(run)), target);
const data = path.join(run, "data");
const mysql = (name) => path.join(mysqlBin, name + ".exe");
const portChecks = [];
const processes = [];
const generated = () => "M443!" + randomBytes(16).toString("hex");
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let stage = "preflight";
let mysqlProcess;
let rootPass;
let cleanupComplete = false;
const pw = {};

function launch(exe, args, env = {}, input) {
  const child = spawn(exe, args, {
    cwd: run,
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true,
    env: { ...process.env, ...env, TMP: run, TEMP: run },
  });
  const entry = { child, output: "", stderr: "", done: false };
  child.stdout.on("data", (b) => {
    entry.output = (entry.output + b).slice(-2_000_000);
  });
  child.stderr.on("data", (b) => {
    entry.stderr = (entry.stderr + b).slice(-2_000_000);
  });
  child.stdin.on("error", () => {});
  child.stdin.end(input);
  entry.closed = new Promise((resolve) => {
    child.on("error", () => {});
    child.on("close", (code) => {
      entry.done = true;
      resolve(code);
    });
  });
  processes.push(entry);
  return entry;
}
async function check(entry, label, timeout = 70000) {
  try {
    assert.equal(await bounded(entry.closed, timeout), 0, label);
    return entry.output;
  } catch {
    throw Error(label);
  }
}
async function bounded(work, ms) {
  let timer;
  try {
    return await Promise.race([
      work,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(Error("timeout")), ms);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}
async function waitFor(fn, label, limit = 50000) {
  const deadline = Date.now() + limit;
  while (Date.now() < deadline) {
    const value = await fn();
    if (value) return value;
    await pause(200);
  }
  throw Error(label);
}
async function freePort() {
  const listener = net.createServer();
  listener.listen(0, "127.0.0.1");
  await once(listener, "listening");
  const { port } = listener.address();
  await new Promise((resolve) => listener.close(resolve));
  if (portChecks.includes(port)) return freePort();
  portChecks.push(port);
  return port;
}
async function listening(port) {
  return new Promise((resolve) => {
    const socket = net.connect(port, "127.0.0.1");
    socket.setTimeout(500);
    const finish = (value) => {
      socket.destroy();
      resolve(value);
    };
    socket.on("connect", () => finish(true));
    socket.on("error", () => finish(false));
    socket.on("timeout", () => finish(false));
  });
}
const mysqlPort = await freePort();
const apiPort = await freePort();
const schema =
  "fixcampus_m421_m443_" + randomBytes(6).toString("hex") + "_test";
const backend = "http://127.0.0.1:" + apiPort;

const envKeys = Object.keys(process.env).filter(
  (k) =>
    k.startsWith("FIXCAMPUS_") ||
    /^(SPRING_|JDK_JAVA_OPTIONS$|JAVA_TOOL_OPTIONS$|_JAVA_OPTIONS$)/i.test(k),
);
const sqlArgs = [
  "--no-defaults",
  "--protocol=TCP",
  "--host=127.0.0.1",
  "--port=" + mysqlPort,
  "--user=root",
];
const sql = (query, args = []) =>
  check(
    launch(
      mysql("mysql"),
      [
        ...sqlArgs,
        "--default-character-set=utf8mb4",
        "--batch",
        "--raw",
        "--skip-column-names",
        ...args,
      ],
      { MYSQL_PWD: rootPass },
      query,
    ),
    "mysql query",
  );
let evidence;
try {
  stage = "mysql initialize";
  const init = launch(mysql("mysqld"), [
    "--no-defaults",
    "--initialize",
    "--skip-log-bin",
    "--datadir=" + data,
    "--console",
  ]);
  await check(init, stage);
  const initial =
    init.stderr.match(
      /temporary password is generated for root@localhost:\s+([^\r\n ]+)/i,
    ) ??
    init.output.match(
      /temporary password is generated for root@localhost:\s+([^\r\n ]+)/i,
    );
  assert.ok(initial, "mysql generated initial credentials");
  rootPass = initial[1];
  stage = "mysql start";
  mysqlProcess = launch(mysql("mysqld"), [
    "--no-defaults",
    "--console",
    "--bind-address=127.0.0.1",
    "--port=" + mysqlPort,
    "--datadir=" + data,
    "--skip-log-bin",
    "--mysqlx=OFF",
    "--innodb-buffer-pool-size=128M",
    "--performance-schema=ON",
    "--innodb-buffer-pool-dump-at-shutdown=OFF",
    "--innodb-buffer-pool-load-at-startup=OFF",
  ]);
  await waitFor(() => {
    if (mysqlProcess.done) throw Error("owned MySQL exited");
    return /ready for connections/i.test(
      mysqlProcess.output + mysqlProcess.stderr,
    );
  }, "mysql readiness");
  stage = "mysql provision";
  const newRoot = generated();
  await sql(
    "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + newRoot + "';\n",
    ["--connect-expired-password"],
  );
  rootPass = newRoot;
  const instance = (await sql("SELECT @@port, @@server_uuid, @@datadir;\n"))
    .trim()
    .split("\t");
  assert.equal(+instance[0], mysqlPort);
  assert.match(instance[1], /^[0-9a-f-]{36}$/);
  assert.equal(
    path.resolve(instance[2]).toLowerCase(),
    path.resolve(data).toLowerCase(),
  );
  for (const key of ["app", "migration", "admin", "worker", "student"])
    pw[key] = generated();
  await sql(
    "CREATE DATABASE " +
      schema +
      " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n" +
      "CREATE USER 'm443app'@'127.0.0.1' IDENTIFIED BY '" +
      pw.app +
      "';\n" +
      "CREATE USER 'm443migration'@'127.0.0.1' IDENTIFIED BY '" +
      pw.migration +
      "';\n" +
      "GRANT SELECT, INSERT, UPDATE, DELETE ON " +
      schema +
      ".* TO 'm443app'@'127.0.0.1';\n" +
      "GRANT ALL PRIVILEGES ON " +
      schema +
      ".* TO 'm443migration'@'127.0.0.1';\n",
  );
  stage = "spring startup";
  const appEnv = Object.fromEntries(envKeys.map((key) => [key, undefined]));
  Object.assign(appEnv, {
    SPRING_PROFILES_ACTIVE: "web-slice",
    SPRING_DATASOURCE_HIKARI_REGISTER_MBEANS: "true",
    FIXCAMPUS_PORT: String(apiPort),
    FIXCAMPUS_WEB_ORIGIN: backend,
    FIXCAMPUS_DB_HOST: "127.0.0.1",
    FIXCAMPUS_DB_PORT: String(mysqlPort),
    FIXCAMPUS_DB_NAME: schema,
    FIXCAMPUS_DB_USERNAME: "m443app",
    FIXCAMPUS_DB_PASSWORD: pw.app,
    FIXCAMPUS_MIGRATION_USERNAME: "m443migration",
    FIXCAMPUS_MIGRATION_PASSWORD: pw.migration,
    FIXCAMPUS_DEMO_ENABLED: "true",
    FIXCAMPUS_DEMO_ADMIN_PASSWORD: pw.admin,
    FIXCAMPUS_DEMO_WORKER_PASSWORD: pw.worker,
    FIXCAMPUS_DEMO_STUDENT_PASSWORD: pw.student,
    FIXCAMPUS_COOKIE_SECURE: "false",
  });
  async function startApp(env) {
    const server = launch(
      java,
      [
        "-Xms128m",
        "-Xmx512m",
        "-Djava.io.tmpdir=" + run,
        "-Dcom.sun.management.jmxremote.local.only=true",
        "-jar",
        jar,
      ],
      env,
    );
    await waitFor(() => {
      if (server.done) {
        const log = server.output + server.stderr;
        const failure = Error("owned Spring process exited");
        failure.diagnostics = {
          exceptions: [
            ...new Set(
              log.match(/\b[A-Za-z][A-Za-z0-9.]+(?:Exception|Error)(?=:)/g) ??
                [],
            ),
          ],
          sqlStates: [
            ...new Set(
              [...log.matchAll(/SQLState\s*:\s*([A-Z0-9]{5})/g)].map(
                (match) => match[1],
              ),
            ),
          ],
          portInUse: /port \d+ was already in use|address already in use/i.test(
            log,
          ),
          flywayValidationFailed:
            /validate failed: migrations have failed validation/i.test(log),
        };
        throw failure;
      }
      return server.output.includes("demo accounts initialized");
    }, "spring readiness");
    return server;
  }
  const application = await startApp(appEnv);
  const poolProbeArgs = [
    "--add-modules",
    "jdk.attach",
    fileURLToPath(new URL("./PoolProbe.java", import.meta.url)),
    String(application.child.pid),
  ];
  const inputs = {
    sql: (query) => sql(query, ["--database=" + schema]),
    size,
    origin: backend,
    credentials: pw,
    stage: (name) => {
      stage = name;
      process.stderr.write("Measuring " + name + "\n");
    },
  };
  let measured;
  if (mode === "study") {
    measured = await study({
      ...inputs,
      useShortLockWait: async () => {
        await inputs.sql("SET GLOBAL innodb_lock_wait_timeout = 1;");
        await check(
          launch(java, [...poolProbeArgs, "--evict-idle"]),
          "Hikari idle eviction",
        );
      },
    });
  } else {
    stage = "Hikari probe startup";
    const probe = launch(java, poolProbeArgs);
    const getPool = () =>
      probe.output
        .trim()
        .split(/\r?\n/)
        .filter((line) => /^\d+\t/.test(line))
        .map((line) => {
          const [at, active, idle, pending, heap, cpu] = line
            .split("\t")
            .map(Number);
          return { at, active, idle, pending, heap, cpu };
        });
    await waitFor(() => {
      if (probe.done) throw Error("Hikari probe process exited");
      return getPool().length >= 2;
    }, "Hikari probe readiness");
    measured = await measure({
      ...inputs,
      getPool,
      paginationOnly: mode === "pagination",
    });
  }
  evidence = {
    status: "PASS",
    mode,
    size,
    platform: os.platform(),
    cpu: os.cpus()[0].model,
    logicalCpuCount: os.availableParallelism(),
    systemMemoryMiB: Math.round(os.totalmem() / 1048576),
    node: process.version,
    java: (await check(launch(java, ["--version"]), "java version")).split(
      /\r?\n/,
      1,
    )[0],
    appHeapLimitMiB: 512,
    appConnectionPoolLimit: 3,
    measured,
  };
} catch (error) {
  const source = error.stack
    ?.split("\n")
    .find((line) => line.includes("scripts/benchmark/"))
    ?.trim();
  evidence = {
    status: "FAIL",
    size,
    stage,
    errorClass: error.name,
    source,
    actual: Number.isFinite(error.actual) ? error.actual : undefined,
    expected: Number.isFinite(error.expected) ? error.expected : undefined,
    diagnostics: error.diagnostics,
  };
} finally {
  try {
    if (mysqlProcess && !mysqlProcess.done && rootPass) {
      const shutdown = launch(mysql("mysqladmin"), [...sqlArgs, "shutdown"], {
        MYSQL_PWD: rootPass,
      });
      await bounded(shutdown.closed, 10000);
    }
  } catch {
    /* fallback stops only ChildProcess objects spawned by this run */
  }
  for (const entry of [...processes].reverse())
    if (!entry.done) entry.child.kill();
  try {
    await bounded(Promise.all(processes.map((p) => p.closed)), 10000);
    assert.ok(
      (await Promise.all(portChecks.map(listening))).every((active) => !active),
    );
    assert.equal(path.dirname(await fs.realpath(run)), target);
    assert.match(path.basename(run), /^m443-runtime-[A-Za-z0-9]+$/);
    // Node removes directory junction entries themselves and never traverses the targets.
    await fs.rm(run, {
      recursive: true,
      force: true,
      maxRetries: 3,
      retryDelay: 150,
    });
    await assert.rejects(fs.stat(run), { code: "ENOENT" });
    cleanupComplete = true;
  } catch {
    evidence.cleanupRuntime = run;
  }
}
evidence.cleanup = cleanupComplete ? "PASS" : "FAILED";
process.stdout.write(JSON.stringify(evidence, null, 2) + "\n");
process.exitCode = evidence.status === "PASS" && cleanupComplete ? 0 : 1;
