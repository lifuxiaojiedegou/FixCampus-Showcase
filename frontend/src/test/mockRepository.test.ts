import { describe, expect, it } from 'vitest';
import { MockRepository } from '../shared/api/mock/mockRepository';
import { users, otherStudent } from '../shared/api/mock/fixtures/users';
import { mockRouter } from '../shared/api/mock/mockRouter';
import { latency } from '../shared/api/mock/latency';
import type { OrderQuery } from '../shared/api/contracts';
import { completeSchema, createSchema } from '../shared/forms/schemas';
import { formatDate } from '../shared/utils/formatDate';

const query: OrderQuery = {
  page: 0,
  size: 20,
  sort: 'createdAt,desc',
  status: '',
  q: '',
};
const input = {
  location: '虚构地点',
  category: 'NETWORK' as const,
  description: '虚构故障',
};
describe('in-memory candidate contract', () => {
  it('scopes both items and total count, and pages stably', async () => {
    const repo = new MockRepository('normal', 0);
    const student = await repo.listOrders(users.student, query);
    const admin = await repo.listOrders(users.admin, query);
    const worker = await repo.listOrders(users.worker, query);
    expect(student.totalElements).toBe(23);
    expect(admin.totalElements).toBe(24);
    expect(worker.totalElements).toBe(16);
    expect(student.items).toHaveLength(20);
    const next = await repo.listOrders(users.student, { ...query, page: 1 });
    expect(next.items).toHaveLength(3);
    expect(
      new Set([...student.items, ...next.items].map((o) => o.id)).size,
    ).toBe(23);
    expect(student.items[0].id).toBe('1001');
  });
  it('does not expose other owners or role-only fields', async () => {
    const repo = new MockRepository('normal', 0);
    await expect(repo.getOrder(users.student, '1024')).rejects.toMatchObject({
      problem: { status: 404 },
    });
    await expect(repo.getOrder(users.worker, '1001')).rejects.toMatchObject({
      problem: { status: 404 },
    });
    const detail = await repo.getOrder(users.student, '1002');
    expect(detail).not.toHaveProperty('student');
    expect(detail).not.toHaveProperty('studentId');
    expect(detail).not.toHaveProperty('workerId');
    const admin = await repo.getOrder(users.admin, '1002');
    expect(admin).toHaveProperty('student');
  });
  it('counts todo independently from filtering and preserves missing history', async () => {
    const repo = new MockRepository('normal', 0);
    expect(repo.todoCount(users.admin)).toBe(4);
    await repo.listOrders(users.admin, { ...query, q: '无匹配' });
    expect(repo.todoCount(users.admin)).toBe(4);
    const missing = await repo.getOrder(users.admin, '1011');
    expect(missing.history.incomplete).toBe(true);
    expect(missing.history.entries[0]).toMatchObject({
      recorded: false,
      actorName: null,
      occurredAt: null,
    });
  });
  it('runs the simulated lifecycle and rejects repeated actions without changing completion early', async () => {
    const repo = new MockRepository('normal', 0);
    await repo.updateOrder(users.admin, '1001', 'ASSIGN', { workerId: '3' });
    await expect(
      repo.updateOrder(users.admin, '1001', 'ASSIGN', { workerId: '3' }),
    ).rejects.toMatchObject({ problem: { status: 409 } });
    await repo.updateOrder(users.worker, '1001', 'START');
    await repo.updateOrder(users.worker, '1001', 'COMPLETE', {
      repairResult: ' 已模拟修好\r\n请确认 ',
    });
    expect((await repo.getOrder(users.student, '1001')).completedAt).toBeNull();
    await repo.updateOrder(users.student, '1001', 'CONFIRM');
    const detail = await repo.getOrder(users.student, '1001');
    expect(detail.status).toBe('COMPLETED');
    expect(detail.completedAt).not.toBeNull();
    expect(detail.repairResult).toBe('已模拟修好\n请确认');
    expect(detail.history.incomplete).toBe(false);
    expect(detail.history.entries.map((event) => event.action)).toEqual([
      'SUBMITTED',
      'ASSIGN',
      'START',
      'COMPLETE',
      'CONFIRM',
    ]);
    for (const event of detail.history.entries) {
      expect(event.recorded).toBe(true);
      expect(event.occurredAt).not.toBeNull();
      expect(event.actorName).not.toBeNull();
    }
  });
  it.each([
    ['1001', 'PENDING_ASSIGNMENT', false],
    ['1002', 'PENDING_REPAIR', true],
    ['1003', 'IN_PROGRESS', true],
    ['1004', 'PENDING_CONFIRMATION', true],
    ['1005', 'COMPLETED', true],
    ['1006', 'CANCELLED', true],
  ] as const)(
    'reports initial %s / %s history honestly',
    async (id, status, incomplete) => {
      const detail = await new MockRepository('normal', 0).getOrder(
        users.admin,
        id,
      );
      expect(detail.status).toBe(status);
      expect(detail.history.incomplete).toBe(incomplete);
      expect(detail.history.entries).toHaveLength(1);
      expect(detail.history.entries[0]).toMatchObject({
        action: 'SUBMITTED',
        recorded: true,
      });
    },
  );
  it('preserves initial history gaps while recording new actions', async () => {
    const repo = new MockRepository('normal', 0);
    await repo.updateOrder(users.worker, '1002', 'START');
    const detail = await repo.getOrder(users.worker, '1002');
    expect(detail.history.incomplete).toBe(true);
    expect(detail.history.entries.map((event) => event.action)).toEqual([
      'SUBMITTED',
      'START',
    ]);
    expect(detail.history.entries[1]).toMatchObject({
      recorded: true,
      actorName: users.worker.displayName,
    });
  });
  it.each(['5', '999', '1', '2', ''])(
    'rejects unavailable assignment target %s without mutation',
    async (workerId) => {
      const repo = new MockRepository('normal', 0);
      const before = await repo.getOrder(users.admin, '1001');
      await expect(
        repo.updateOrder(users.admin, '1001', 'ASSIGN', { workerId }),
      ).rejects.toMatchObject({
        problem: { status: 400, code: 'VALIDATION_ERROR' },
      });
      expect(await repo.getOrder(users.admin, '1001')).toEqual(before);
    },
  );
  it('restricts cancel, valid workers, and role actions', async () => {
    const repo = new MockRepository('normal', 0);
    await expect(
      repo.updateOrder(users.admin, '1001', 'ASSIGN', { workerId: '999' }),
    ).rejects.toMatchObject({ problem: { status: 400 } });
    await expect(
      repo.updateOrder(users.student, '1001', 'ASSIGN', { workerId: '3' }),
    ).rejects.toMatchObject({ problem: { status: 403 } });
    await repo.updateOrder(users.student, '1001', 'CANCEL');
    await expect(
      repo.updateOrder(users.student, '1002', 'CANCEL'),
    ).rejects.toMatchObject({ problem: { status: 409 } });
    expect((await repo.getOrder(users.student, '1001')).status).toBe(
      'CANCELLED',
    );
    expect(
      (await repo.getOrder(users.student, '1001')).history.incomplete,
    ).toBe(false);
  });
  it('creates normalized data, clones results and resets with a new repository', async () => {
    const repo = new MockRepository('normal', 0);
    const created = await repo.createOrder(users.student, {
      ...input,
      location: ' 虚构地点 ',
      description: ' a\r\nb ',
    });
    const detail = await repo.getOrder(users.student, created.id);
    expect(detail.history.incomplete).toBe(false);
    expect(detail.description).toBe('a\nb');
    detail.history.entries[0].actorName = 'tampered';
    expect(
      (await repo.getOrder(users.student, created.id)).history.entries[0]
        .actorName,
    ).not.toBe('tampered');
    await expect(
      new MockRepository('normal', 0).getOrder(users.student, created.id),
    ).rejects.toMatchObject({ problem: { status: 404 } });
    await expect(repo.createOrder(users.admin, input)).rejects.toMatchObject({
      problem: { status: 403 },
    });
    await expect(repo.getOrder(otherStudent, '1001')).rejects.toMatchObject({
      problem: { status: 404 },
    });
  });
  it.each([{ page: -1 }, { size: 0 }, { size: 51 }, { q: '😀'.repeat(121) }])(
    'rejects invalid query %j',
    async (invalid) => {
      await expect(
        new MockRepository('normal', 0).listOrders(users.student, {
          ...query,
          ...invalid,
        }),
      ).rejects.toMatchObject({ problem: { status: 400 } });
    },
  );
  it('uses literal location search and exact numeric ID search', async () => {
    const repo = new MockRepository('normal', 0);
    expect(
      (await repo.listOrders(users.student, { ...query, q: '#1001' })).items[0]
        .id,
    ).toBe('1001');
    expect(
      (await repo.listOrders(users.student, { ...query, q: '%' }))
        .totalElements,
    ).toBe(0);
    expect(
      (await repo.listOrders(users.student, { ...query, q: '设施出现异常' }))
        .totalElements,
    ).toBe(0);
  });
  it.each([
    ['unavailable', 503],
    ['forbidden', 403],
    ['expired', 401],
  ] as const)('preserves simulated %s failures', async (scenario, status) => {
    await expect(
      new MockRepository(scenario, 0).listOrders(users.student, query),
    ).rejects.toMatchObject({ problem: { status } });
  });
  it('supports empty/conflict and refuses unknown routes', async () => {
    expect(
      (await new MockRepository('empty', 0).listOrders(users.student, query))
        .totalElements,
    ).toBe(0);
    await expect(
      new MockRepository('conflict', 0).createOrder(users.student, input),
    ).rejects.toMatchObject({ problem: { status: 409 } });
    await expect(
      mockRouter(
        new MockRepository('normal', 0),
        'POST',
        '/unknown',
        users.student,
        query,
      ),
    ).rejects.toMatchObject({ problem: { code: 'MOCK_ROUTE_NOT_FOUND' } });
  });
  it('supports cancellation before and during delay', async () => {
    const first = new AbortController();
    first.abort();
    await expect(latency(first.signal, 10)).rejects.toHaveProperty(
      'name',
      'AbortError',
    );
    const second = new AbortController();
    const pending = latency(second.signal, 100);
    second.abort();
    await expect(pending).rejects.toHaveProperty('name', 'AbortError');
  });
});
describe('Unicode and display boundaries', () => {
  it('counts code points and normalizes line endings', () => {
    expect(
      createSchema.safeParse({ ...input, location: '😀'.repeat(120) }).success,
    ).toBe(true);
    expect(
      createSchema.safeParse({ ...input, location: '😀'.repeat(121) }).success,
    ).toBe(false);
    expect(
      createSchema.safeParse({ ...input, description: ' \r\n ' }).success,
    ).toBe(false);
    expect(
      completeSchema.safeParse({ repairResult: '😀'.repeat(1001) }).success,
    ).toBe(false);
  });
  it('formats missing and invalid dates honestly', () => {
    expect(formatDate(null)).toBe('未记录');
    expect(formatDate('bad')).toBe('日期无效');
  });
});
