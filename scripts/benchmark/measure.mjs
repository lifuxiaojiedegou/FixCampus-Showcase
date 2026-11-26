import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";
import { performance } from "node:perf_hooks";

const digitRows = Array.from(
  { length: 10 },
  (_, n) => "SELECT " + n + (n === 0 ? " AS d" : ""),
).join(" UNION ALL ");
const sequence = Array.from(
  { length: 5 },
  (_, n) => "(" + digitRows + ") d" + n,
).join(" CROSS JOIN ");
const sequenceValue =
  Array.from({ length: 5 }, (_, n) => "d" + n + ".d * " + 10 ** n).join(" + ") +
  " + 1";

export async function seed(sql, size, actor) {
  const admin = actor.admin;
  const student = actor.student;
  const worker = actor.worker;
  assert.equal((await sql("SELECT COUNT(*) FROM repair_orders;")).trim(), "0");
  const source = (role, prefix, count, id) =>
    "INSERT INTO users(username, password, real_name, role, created_at) " +
    "SELECT CONCAT('" +
    prefix +
    "',LPAD(n,2,'0')), u.password, CONCAT('Synthetic " +
    prefix +
    " ',n), '" +
    role +
    "'," +
    "'2025-01-01 00:00:00' FROM benchmark_sequence JOIN users u ON u.id=" +
    id +
    " WHERE n<=" +
    count +
    " ORDER BY n;";
  const result = await sql(
    "CREATE TEMPORARY TABLE benchmark_sequence(n INT PRIMARY KEY);" +
      "INSERT INTO benchmark_sequence SELECT " +
      sequenceValue +
      " FROM " +
      sequence +
      " WHERE " +
      sequenceValue +
      "<=" +
      size +
      ";" +
      "START TRANSACTION;" +
      source("STUDENT", "benchstudent", 49, student) +
      source("WORKER", "benchworker", 11, worker) +
      "SET @first_student=(SELECT id FROM users WHERE username='benchstudent01');" +
      "SET @first_worker=(SELECT id FROM users WHERE username='benchworker01');" +
      "INSERT INTO repair_orders (id, student_id, worker_id, location, category, description, status, repair_result, created_at, completed_at) " +
      "SELECT n, IF(MOD(n,4)=0," +
      student +
      ",@first_student+MOD(FLOOR(n/4)*13+MOD(n,17),49))," +
      "CASE WHEN s IN ('PENDING_ASSIGNMENT','CANCELLED') THEN NULL" +
      " WHEN MOD(n,10) IN (0,1,2) THEN " +
      worker +
      " ELSE @first_worker+MOD(n*7+FLOOR(n/13),11) END," +
      "CONCAT(IF(MOD(n,101)=0,'LAB-RARE ','ZONE-'),LPAD(MOD(n,10),2,'0'),'-',LPAD(n,6,'0'))," +
      "ELT(1+MOD(n,5),'WATER_ELECTRICITY','DOORS_FURNITURE','NETWORK','AIR_CONDITIONING','OTHER')," +
      "'Synthetic benchmark work order',s,IF(s IN ('PENDING_CONFIRMATION','COMPLETED'),'Synthetic repair result',NULL)," +
      "ts,IF(s='COMPLETED',ts+INTERVAL 4 MINUTE,NULL) FROM (SELECT n," +
      "TIMESTAMP('2025-01-01 00:00:00')+INTERVAL (n*97) SECOND AS ts," +
      "CASE WHEN bucket<35 THEN 'PENDING_ASSIGNMENT' WHEN bucket<55 THEN 'PENDING_REPAIR'" +
      " WHEN bucket<70 THEN 'IN_PROGRESS' WHEN bucket<80 THEN 'PENDING_CONFIRMATION'" +
      " WHEN bucket<95 THEN 'COMPLETED' ELSE 'CANCELLED' END AS s" +
      " FROM (SELECT n, MOD(FLOOR((n-1)/50),100) AS bucket FROM benchmark_sequence) numbers) samples ORDER BY n;" +
      "INSERT INTO order_events(order_id, action, actor_id, actor_role, actor_name, occurred_at, detail) " +
      "SELECT o.id, e.action, u.id, u.role, u.real_name, o.created_at+INTERVAL e.minutes MINUTE," +
      "CASE e.action WHEN 'ASSIGNED' THEN 'Synthetic assignment' WHEN 'REPAIR_COMPLETED' THEN o.repair_result END " +
      "FROM repair_orders o JOIN (SELECT 'SUBMITTED' AS action, 0 AS minutes UNION ALL SELECT 'ASSIGNED',1" +
      " UNION ALL SELECT 'REPAIR_STARTED',2 UNION ALL SELECT 'REPAIR_COMPLETED',3" +
      " UNION ALL SELECT 'CONFIRMED',4 UNION ALL SELECT 'CANCELLED',1) e" +
      " ON e.action='SUBMITTED' OR (e.action='ASSIGNED' AND o.worker_id IS NOT NULL)" +
      " OR (e.action='REPAIR_STARTED' AND o.status IN ('IN_PROGRESS','PENDING_CONFIRMATION','COMPLETED'))" +
      " OR (e.action='REPAIR_COMPLETED' AND o.status IN ('PENDING_CONFIRMATION','COMPLETED'))" +
      " OR (e.action='CONFIRMED' AND o.status='COMPLETED') OR (e.action='CANCELLED' AND o.status='CANCELLED')" +
      " JOIN users u ON u.id=CASE WHEN e.action='ASSIGNED' THEN " +
      admin +
      " WHEN e.action IN ('REPAIR_STARTED','REPAIR_COMPLETED') THEN o.worker_id ELSE o.student_id END" +
      " ORDER BY o.id, e.minutes; COMMIT; ANALYZE TABLE repair_orders,order_events;",
  );
  assert.doesNotMatch(result, /\tError\t/);
  const [actual, events, students, workers, rare] = (
    await sql(
      "SELECT COUNT(*),(SELECT COUNT(*) FROM order_events),COUNT(DISTINCT student_id),COUNT(DISTINCT worker_id)," +
        "SUM(location LIKE '%LAB-RARE%') FROM repair_orders;",
    )
  )
    .trim()
    .split("\t")
    .map(Number);
  assert.equal(actual, size);
  assert.equal(events, (size * 245) / 100);
  assert.equal(students, 50);
  assert.equal(workers, 12);
  assert.equal(rare, Math.floor(size / 101));
  const byStatus = Object.fromEntries(
    (await sql("SELECT status,COUNT(*) FROM repair_orders GROUP BY status;"))
      .trim()
      .split(/\r?\n/)
      .map((line) => {
        const [key, value] = line.split("\t");
        return [key, Number(value)];
      }),
  );
  return { orders: actual, events, students, workers, rare, byStatus };
}

export async function login(origin, username, credential) {
  let cookies;
  let csrf;
  function remember(response) {
    const payload = response.headers
      .getSetCookie()
      .map((value) => value.split(";", 1)[0]);
    if (payload.length) cookies = payload.join("; ");
    return response;
  }
  async function request(route, options = {}) {
    const { method = "GET", payload } = options;
    const headers = { origin, ...(cookies ? { cookie: cookies } : {}) };
    if (method === "POST") {
      headers[csrf.headerName] = csrf.token;
      headers["content-type"] = "application/json";
    }
    const start = performance.now();
    const response = remember(
      await fetch(origin + "/api/v1" + route, {
        method,
        headers,
        body: payload === undefined ? undefined : JSON.stringify(payload),
        signal: AbortSignal.timeout(12000),
      }),
    );
    const raw = await response.text();
    return {
      status: response.status,
      elapsed: performance.now() - start,
      data: raw ? JSON.parse(raw) : null,
    };
  }
  async function refreshCsrf() {
    const response = await request("/auth/csrf");
    assert.equal(response.status, 200);
    csrf = response.data;
  }
  await refreshCsrf();
  const result = await request("/auth/login", {
    method: "POST",
    payload: { username, password: credential },
  });
  assert.equal(result.status, 200, "benchmark actor login");
  await refreshCsrf();
  return { id: result.data.id, request };
}

function summary(results, durationMs) {
  const sorted = results.map((result) => result.elapsed).sort((a, b) => a - b);
  const p = (fraction) =>
    +sorted[Math.ceil(sorted.length * fraction) - 1].toFixed(2);
  const statuses = {};
  for (const result of results)
    statuses[result.status] = (statuses[result.status] ?? 0) + 1;
  const errors = results.filter((result) => result.status >= 400).length;
  return {
    samples: sorted.length,
    durationMs: +durationMs.toFixed(2),
    perSecond: +((1000 * sorted.length) / durationMs).toFixed(2),
    p50Ms: p(0.5),
    p95Ms: p(0.95),
    p99Ms: p(0.99),
    statuses,
    errorRate: errors / results.length,
  };
}

async function batch(run, concurrency, count) {
  const start = performance.now();
  const startedAt = Date.now();
  let next = 0;
  const results = await Promise.all(
    Array.from({ length: concurrency }, async () => {
      const local = [];
      while (next < count) local.push(await run(next++));
      return local;
    }),
  );
  return {
    measurements: summary(results.flat(), performance.now() - start),
    startedAt,
    endedAt: Date.now(),
  };
}

function poolWindow(getPool, start, end) {
  const points = getPool().filter(
    (row) => row.at >= start - 50 && row.at <= end + 50,
  );
  assert.ok(points.length >= 2, "Hikari sample window");
  return {
    intervalMs: 50,
    samples: points.length,
    maxActive: Math.max(...points.map((p) => p.active)),
    minIdle: Math.min(...points.map((p) => p.idle)),
    maxPending: Math.max(...points.map((p) => p.pending)),
    peakHeapMiB: +(Math.max(...points.map((p) => p.heap)) / 1048576).toFixed(1),
    cpuMs: +((points.at(-1).cpu - points[0].cpu) / 1e6).toFixed(1),
  };
}

export async function measure({
  sql,
  size,
  origin,
  credentials,
  getPool,
  stage,
  paginationOnly,
}) {
  stage("authenticate synthetic actors");
  const admin = await login(origin, "admin01", credentials.admin);
  const student = await login(origin, "student01", credentials.student);
  const worker = await login(origin, "worker01", credentials.worker);
  const actor = { admin: admin.id, student: student.id, worker: worker.id };
  for (const id of Object.values(actor)) assert.match(id, /^[1-9][0-9]*$/);
  stage("seed and verify " + size);
  const distribution = await seed(sql, size, actor);
  const [
    [
      version,
      lockWait,
      poolBytes,
      maxConnections,
      bufferPoolDump,
      bufferPoolLoad,
    ],
  ] = (
    await sql(
      "SELECT VERSION(),@@global.innodb_lock_wait_timeout,@@innodb_buffer_pool_size,@@max_connections," +
        "@@innodb_buffer_pool_dump_at_shutdown,@@innodb_buffer_pool_load_at_startup;",
    )
  )
    .trim()
    .split(/\r?\n/)
    .map((line) => line.split("\t"));
  assert.equal(lockWait, "50");
  const mysql = {
    version,
    lockWaitSeconds: +lockWait,
    bufferPoolBytes: +poolBytes,
    maxConnections: +maxConnections,
    bufferPoolDump,
    bufferPoolLoad,
  };
  async function expectPage(session, route, count, ids) {
    const result = await session.request(route);
    const params = new URL(route, origin).searchParams;
    const page = Number(params.get("page") ?? 0);
    const size = Number(params.get("size") ?? 20);
    const sort = params.get("sort") ?? "createdAt,desc";
    assert.equal(result.status, 200, route);
    assert.equal(result.data.page, page, route);
    assert.equal(result.data.size, size, route);
    assert.equal(result.data.sort, sort, route);
    assert.equal(result.data.totalElements, count, route);
    assert.equal(result.data.totalPages, Math.ceil(count / size), route);
    assert.deepEqual(
      result.data.items.map((item) => item.id),
      ids,
      route,
    );
    return result;
  }
  const queries = [
    ["admin-list", admin, "/admin/orders", "1=1", 0],
    [
      "admin-filter-status",
      admin,
      "/admin/orders?status=PENDING_ASSIGNMENT",
      "status='PENDING_ASSIGNMENT'",
      0,
    ],
    [
      "admin-filter-common",
      admin,
      "/admin/orders?q=ZONE-00",
      "(id=NULL OR location LIKE '%ZONE-00%' ESCAPE '!')",
      0,
    ],
    [
      "admin-filter-rare",
      admin,
      "/admin/orders?q=LAB-RARE",
      "(id=NULL OR location LIKE '%LAB-RARE%' ESCAPE '!')",
      0,
    ],
    [
      "admin-deep-page",
      admin,
      "/admin/orders?page=" + (size / 20 - 1),
      "1=1",
      size - 20,
    ],
    ["student-list", student, "/orders", "student_id=" + actor.student, 0],
    ["worker-list", worker, "/worker/orders", "worker_id=" + actor.worker, 0],
  ];
  const readResults = [];
  const plans = {};
  for (const [name, session, route, where, offset] of queries) {
    stage(name + " " + size);
    const from = " FROM repair_orders WHERE " + where;
    const countSql = "SELECT COUNT(*)" + from;
    const pageSql =
      "SELECT id,location,category,status,created_at" +
      from +
      " ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET " +
      offset;
    const count = +(await sql(countSql + ";")).trim();
    const ids = (await sql(pageSql + ";"))
      .trim()
      .split(/\r?\n/)
      .map((row) => row.split("\t")[0]);
    const run = () => expectPage(session, route, count, ids);
    const firstRequestMs = +(await run()).elapsed.toFixed(2);
    for (let i = 0; i < 8; i++) await run();
    const warm = await batch(run, 4, 120);
    readResults.push({
      name,
      firstRequestMs,
      ...warm.measurements,
      pool: poolWindow(getPool, warm.startedAt, warm.endedAt),
    });
    plans[name] = {
      countSql,
      pageSql,
      count: (await sql("EXPLAIN ANALYZE " + countSql + ";")).trim(),
      page: (await sql("EXPLAIN ANALYZE " + pageSql + ";")).trim(),
    };
  }

  stage("admin detail " + size);
  const detail = async () => {
    const response = await admin.request("/admin/orders/" + size);
    assert.equal(response.status, 200);
    assert.equal(response.data.id, String(size));
    assert.ok(response.data.history.entries.length > 0);
    return response;
  };
  const firstRequestMs = +(await detail()).elapsed.toFixed(2);
  for (let i = 0; i < 8; i++) await detail();
  const details = await batch(detail, 4, 120);
  readResults.push({
    name: "admin-detail",
    firstRequestMs,
    ...details.measurements,
    pool: poolWindow(getPool, details.startedAt, details.endedAt),
  });

  if (paginationOnly) {
    stage("pagination contract and actual statement");
    const replacementSql =
      "SELECT o.id,o.location,o.category,o.status,o.created_at FROM (" +
      "SELECT id,created_at FROM repair_orders ORDER BY created_at DESC,id DESC" +
      " LIMIT 20 OFFSET " +
      (size - 20) +
      ") page JOIN repair_orders o ON o.id=page.id ORDER BY page.created_at DESC,page.id DESC";
    const digest = (
      await sql(
        "SELECT DIGEST_TEXT,COUNT_STAR FROM performance_schema.events_statements_summary_by_digest" +
          " WHERE SCHEMA_NAME=DATABASE() AND DIGEST_TEXT LIKE 'SELECT %repair_orders%LIMIT%JOIN%repair_orders%ORDER BY%';",
      )
    ).trim();
    assert.ok(
      digest && Number(digest.split("\t").at(-1)) >= 129,
      "candidate REST statement",
    );
    const deepPagePlan = (
      await sql("EXPLAIN ANALYZE " + replacementSql + ";")
    ).trim();

    await sql(
      "UPDATE repair_orders o JOIN order_events e ON e.order_id=o.id AND e.action='SUBMITTED'" +
        " SET o.created_at='2025-01-01 00:00:00', e.occurred_at='2025-01-01 00:00:00' WHERE o.id IN (1,2,3);",
    );
    await expectPage(
      admin,
      "/admin/orders?page=2&size=1&sort=createdAt,asc",
      size,
      ["3"],
    );
    await expectPage(
      admin,
      "/admin/orders?page=" + (size - 3) + "&size=1",
      size,
      ["3"],
    );
    await expectPage(admin, "/admin/orders?page=1000000&size=50", size, []);
    const rareCount = Math.floor(size / 101);
    await expectPage(
      admin,
      "/admin/orders?page=1&size=1&q=LAB-RARE",
      rareCount,
      [String((rareCount - 1) * 101)],
    );
    assert.equal((await worker.request("/admin/orders?page=1")).status, 403);
    return {
      distribution,
      mysql,
      readResults,
      plans,
      pagination: {
        digest,
        deepPageSql: replacementSql,
        deepPagePlan,
        checked: [
          "asc-tied-timestamps",
          "desc-tied-timestamps",
          "out-of-range-page",
          "filtered-second-page",
          "worker-denied",
        ],
      },
    };
  }

  stage("concurrent writes " + size);
  const pending = (
    await sql(
      "SELECT id FROM repair_orders WHERE status='PENDING_ASSIGNMENT' AND student_id=" +
        actor.student +
        " ORDER BY id LIMIT 50;",
    )
  )
    .trim()
    .split(/\s+/);
  assert.equal(pending.length, 50);
  for (const id of pending) assert.match(id, /^[1-9][0-9]*$/);
  const write = (session, id, action, payload) =>
    session.request("/orders/" + id + "/" + action, {
      method: "POST",
      payload,
    });
  const assign = (id) => write(admin, id, "assign", { workerId: actor.worker });
  const cancel = (id) => write(student, id, "cancel", undefined);
  const operations = await batch((i) => assign(pending[i]), 6, 24);
  assert.deepEqual(operations.measurements.statuses, { 204: 24 });
  const writeResults = [
    {
      name: "parallel-assign-distinct",
      ...operations.measurements,
      pool: poolWindow(getPool, operations.startedAt, operations.endedAt),
    },
  ];
  for (const [name, ids, second] of [
    ["same-order-assign", pending.slice(24, 36), assign],
    ["cancel-versus-assign", pending.slice(36, 48), cancel],
  ]) {
    const start = performance.now(),
      startAt = Date.now(),
      results = [];
    for (const id of ids) {
      const pair = await Promise.all([assign(id), second(id)]);
      assert.deepEqual(pair.map((r) => r.status).sort(), [204, 409]);
      const wonByCancel = second === cancel && pair[1].status === 204;
      const [status, event] = (
        await sql(
          "SELECT o.status,(SELECT GROUP_CONCAT(e.action ORDER BY e.id) FROM order_events e WHERE e.order_id=o.id) FROM repair_orders o WHERE o.id=" +
            id +
            ";",
        )
      )
        .trim()
        .split("\t");
      assert.equal(status, wonByCancel ? "CANCELLED" : "PENDING_REPAIR");
      assert.equal(
        event,
        wonByCancel ? "SUBMITTED,CANCELLED" : "SUBMITTED,ASSIGNED",
      );
      results.push(...pair);
    }
    writeResults.push({
      name,
      ...summary(results, performance.now() - start),
      expectedConflicts: ids.length,
      unexpectedErrorRate: 0,
      pool: poolWindow(getPool, startAt, Date.now()),
    });
  }

  stage("held row lock " + size);
  const lockId = pending[48];
  const rowWaits = async () =>
    +(
      await sql("SELECT COUNT(*) FROM performance_schema.data_lock_waits;")
    ).trim();
  const holder = sql(
    "START TRANSACTION; SELECT id FROM repair_orders WHERE id=" +
      lockId +
      " FOR UPDATE; SELECT SLEEP(2); ROLLBACK;",
  );
  async function waitUntil(check) {
    for (let i = 0; i < 40; i++) {
      const value = await check();
      if (value) return value;
      await delay(25);
    }
    throw Error("timed out waiting for MySQL lock observation");
  }
  try {
    await waitUntil(
      async () =>
        +(
          await sql(
            "SELECT COUNT(*) FROM performance_schema.data_locks WHERE OBJECT_NAME='repair_orders' AND INDEX_NAME='PRIMARY' AND LOCK_STATUS='GRANTED' AND LOCK_DATA='" +
              lockId +
              "';",
          )
        ).trim(),
    );
    const startAt = Date.now();
    const [attempts, observedDbWaits] = await Promise.all([
      Promise.all(Array.from({ length: 6 }, () => assign(lockId))),
      waitUntil(rowWaits),
    ]);
    assert.deepEqual(
      attempts.map((r) => r.status).sort(),
      [204, 409, 409, 409, 409, 409],
    );
    const pool = poolWindow(getPool, startAt, Date.now());
    assert.ok(pool.maxPending > 0, "connection queue observed");
    writeResults.push({
      name: "held-lock-plus-pool-queue",
      ...summary(attempts, Math.max(...attempts.map((r) => r.elapsed))),
      observedDbWaits,
      expectedConflicts: 5,
      unexpectedErrorRate: 0,
      pool,
    });
  } finally {
    await holder;
  }

  const statements = (
    await sql(
      "SELECT REPLACE(DIGEST_TEXT,CHAR(9),' '),COUNT_STAR,SUM_ROWS_EXAMINED,ROUND(SUM_TIMER_WAIT/1000000000,2)" +
        " FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=DATABASE()" +
        " AND (DIGEST_TEXT LIKE 'SELECT%repair_orders%' OR DIGEST_TEXT LIKE 'SELECT%order_events%')" +
        " ORDER BY SUM_TIMER_WAIT DESC LIMIT 12;",
    )
  ).trim();
  return {
    distribution,
    mysql,
    readResults,
    writeResults,
    plans,
    statements,
  };
}
