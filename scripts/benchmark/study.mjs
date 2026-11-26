import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";
import { login, seed } from "./measure.mjs";

export async function study({
  sql,
  size,
  origin,
  credentials,
  useShortLockWait,
  stage,
}) {
  stage("authenticate and seed comparison");
  const admin = await login(origin, "admin01", credentials.admin);
  const student = await login(origin, "student01", credentials.student);
  const worker = await login(origin, "worker01", credentials.worker);
  const actors = { admin: admin.id, student: student.id, worker: worker.id };
  for (const id of Object.values(actors)) assert.match(id, /^[1-9][0-9]*$/);
  const distribution = await seed(sql, size, actors);

  stage("compare deep-page SQL");
  function pageQueries(offset, direction) {
    const columns = "id,location,category,status,created_at";
    return [
      "SELECT " +
        columns +
        " FROM repair_orders WHERE 1=1 ORDER BY created_at " +
        direction +
        ",id " +
        direction +
        " LIMIT 20 OFFSET " +
        offset,
      "SELECT " +
        columns
          .split(",")
          .map((column) => "o." + column)
          .join(",") +
        " FROM (SELECT id,created_at FROM repair_orders ORDER BY created_at " +
        direction +
        ",id " +
        direction +
        " LIMIT 20 OFFSET " +
        offset +
        ") page JOIN repair_orders o ON o.id=page.id ORDER BY page.created_at " +
        direction +
        ",page.id " +
        direction,
    ];
  }
  const checkedOffsets = [0, 20, size / 2, size - 20];
  for (const offset of checkedOffsets) {
    for (const direction of ["ASC", "DESC"]) {
      const [existing, proposed] = pageQueries(offset, direction);
      assert.equal(
        (await sql(existing + ";")).trim(),
        (await sql(proposed + ";")).trim(),
      );
    }
  }
  const [existing, proposed] = pageQueries(size - 20, "DESC");
  const rootTime = (plan) => {
    const match = plan.match(/actual time=[\d.]+\.\.([\d.]+) rows=/);
    assert.ok(match, "EXPLAIN ANALYZE root timing");
    return +match[1];
  };
  const measurements = { existing: [], proposed: [] };
  let plans = {};
  for (let i = 0; i < 7; i++) {
    const queries = Object.entries({ existing, proposed });
    if (i % 2 !== 0) queries.reverse();
    for (const [name, query] of queries) {
      const output = (await sql("EXPLAIN ANALYZE " + query + ";")).trim();
      plans[name] = output;
      if (i > 0) measurements[name].push(rootTime(output));
    }
  }
  const median = (array) => {
    const values = [...array].sort((a, b) => a - b);
    return (values[2] + values[3]) / 2;
  };

  stage("compare lock wait settings");
  const ids = (
    await sql(
      "SELECT id FROM repair_orders WHERE status='PENDING_ASSIGNMENT' AND student_id=" +
        actors.student +
        " ORDER BY id LIMIT 2;",
    )
  )
    .trim()
    .split(/\s+/);
  assert.equal(ids.length, 2);
  for (const id of ids) assert.match(id, /^[1-9][0-9]*$/);
  async function observe(query) {
    for (let i = 0; i < 50; i++) {
      const sample = (await sql(query)).trim();
      if (sample && sample !== "0" && sample !== "NULL") return sample;
      await delay(20);
    }
    throw Error("MySQL observation timed out");
  }
  async function checkWait(id, expectedWait, expectedStatus) {
    const sessionWait = +(await observe(
      "SELECT GROUP_CONCAT(DISTINCT v.VARIABLE_VALUE) FROM performance_schema.variables_by_thread v" +
        " JOIN performance_schema.threads t ON t.THREAD_ID=v.THREAD_ID WHERE t.PROCESSLIST_USER='m443app'" +
        " AND v.VARIABLE_NAME='innodb_lock_wait_timeout';",
    ));
    assert.equal(sessionWait, expectedWait);
    const holder = sql(
      "START TRANSACTION; SELECT id FROM repair_orders WHERE id=" +
        id +
        " FOR UPDATE; SELECT SLEEP(2.5); ROLLBACK;",
    );
    try {
      await observe(
        "SELECT COUNT(*) FROM performance_schema.data_locks WHERE OBJECT_NAME='repair_orders' AND INDEX_NAME='PRIMARY' AND LOCK_STATUS='GRANTED' AND LOCK_DATA='" +
          id +
          "';",
      );
      const [attempt] = await Promise.all([
        admin.request("/orders/" + id + "/assign", {
          method: "POST",
          payload: { workerId: actors.worker },
        }),
        observe(
          "SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits;",
        ),
      ]);
      assert.equal(attempt.status, expectedStatus);
      const [status, event] = (
        await sql(
          "SELECT o.status,(SELECT GROUP_CONCAT(e.action ORDER BY e.id) FROM order_events e WHERE e.order_id=o.id)" +
            " FROM repair_orders o WHERE o.id=" +
            id +
            ";",
        )
      )
        .trim()
        .split("\t");
      assert.equal(
        status,
        expectedStatus === 204 ? "PENDING_REPAIR" : "PENDING_ASSIGNMENT",
      );
      assert.equal(
        event,
        expectedStatus === 204 ? "SUBMITTED,ASSIGNED" : "SUBMITTED",
      );
      if (expectedStatus === 503)
        assert.equal(attempt.data.code, "TEMPORARILY_UNAVAILABLE");
      return {
        sessionWaitSeconds: sessionWait,
        heldForSeconds: 2.5,
        httpStatus: attempt.status,
        problemCode: attempt.data?.code ?? null,
        elapsedMs: +attempt.elapsed.toFixed(2),
        status,
        event,
      };
    } finally {
      await holder;
    }
  }
  const defaultLock = await checkWait(ids[0], 50, 204);
  stage("renew pool sessions with short lock wait");
  await useShortLockWait();
  assert.equal((await admin.request("/admin/orders/" + ids[1])).status, 200);
  stage("measure short lock-wait control");
  const shortLock = await checkWait(ids[1], 1, 503);
  return {
    distribution,
    deepPage: {
      offset: size - 20,
      checkedOffsets,
      orderings: ["ASC", "DESC"],
      countQuery: "unchanged",
      queries: { existing, proposed },
      plans,
      measurementsMs: measurements,
      medianMs: {
        existing: median(measurements.existing),
        proposed: median(measurements.proposed),
      },
    },
    lockWait: { defaultLock, shortLock },
  };
}
