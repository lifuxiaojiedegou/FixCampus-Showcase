import { beforeEach, expect, it, vi } from 'vitest';
import {
  StudentClient,
  type OrderOperation,
} from '../shared/api/rest/studentClient';
import type { Role } from '../shared/api/contracts';
const transport = vi.mocked(fetch);
const response = (body: unknown, status = 200) =>
  new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: {
      'Content-Type':
        status >= 400 ? 'application/problem+json' : 'application/json',
    },
  });
const token = () =>
  response({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
const identity = (role: Role) => ({
  id: '21',
  username: 'fictional',
  displayName: '虚构账号',
  role,
});
const problem = (status: number, code: string) =>
  response({ status, code, message: code }, status);
beforeEach(() => {
  transport.mockReset();
});
async function clientFor(role: Role, changed = vi.fn()) {
  const client = new StudentClient(changed);
  transport
    .mockResolvedValueOnce(token())
    .mockResolvedValueOnce(response(identity(role)));
  await client.refresh();
  return client;
}

it('CSRF confirmation stays revoked after a token-only partial refresh, across view remounts', async () => {
  const client = await clientFor('WORKER');
  const original = client.writeConfirmation();
  transport.mockResolvedValueOnce(problem(403, 'CSRF_REJECTED'));
  await expect(client.operate('101', 'start')).rejects.toThrow('CSRF_REJECTED');
  expect(client.writeConfirmation()).toBeNull();
  transport
    .mockResolvedValueOnce(token())
    .mockResolvedValueOnce(problem(503, 'TEMPORARILY_UNAVAILABLE'));
  await expect(client.refresh()).rejects.toThrow('TEMPORARILY_UNAVAILABLE');
  expect(client.writeConfirmation()).toBeNull();
  await expect(client.operate('101', 'start')).rejects.toThrow('手动提交');
  expect(transport).toHaveBeenCalledTimes(5);
  transport
    .mockResolvedValueOnce(token())
    .mockResolvedValueOnce(response(identity('WORKER')));
  await client.refresh();
  expect(client.writeConfirmation()!).toBeGreaterThan(original!);
  expect(transport).toHaveBeenCalledTimes(7);
});

it.each([
  ['STUDENT', '/orders'],
  ['ADMIN', '/admin/orders'],
  ['WORKER', '/worker/orders'],
] as const)(
  'routes %s only using server identity and encodes filters',
  async (role, prefix) => {
    const client = await clientFor(role);
    transport
      .mockResolvedValueOnce(response({ items: [] }))
      .mockResolvedValueOnce(response({ id: '101' }));
    await client.list(2, {
      status: 'PENDING_REPAIR',
      q: 'A&B%#',
      sort: 'createdAt,asc',
    });
    await client.detail('101');
    const url = new URL(
      String(transport.mock.calls[2][0]),
      'https://local.invalid',
    );
    expect(url.pathname).toBe('/api/v1' + prefix);
    expect(url.searchParams.get('q')).toBe('A&B%#');
    expect(url.searchParams.get('page')).toBe('2');
    expect(url.searchParams.get('size')).toBe('20');
    expect(transport.mock.calls[3][0]).toBe('/api/v1' + prefix + '/101');
  },
);
it.each(['assign', 'start', 'complete', 'confirm', 'cancel'] as const)(
  'sends one protected %s operation without inventing an actor',
  async (action) => {
    const client = await clientFor(
      action === 'assign'
        ? 'ADMIN'
        : action === 'confirm' || action === 'cancel'
          ? 'STUDENT'
          : 'WORKER',
    );
    transport.mockResolvedValueOnce(response(null, 204));
    const body =
      action === 'assign'
        ? { workerId: '32' }
        : action === 'complete'
          ? { repairResult: '已修复' }
          : undefined;
    await client.operate('101', action, body);
    const [url, options] = transport.mock.calls[2];
    expect(url).toBe(`/api/v1/orders/101/${action}`);
    expect(options?.method).toBe('POST');
    expect(options?.body).toBe(JSON.stringify(body ?? {}));
    expect(options?.headers).toEqual({
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': 'test-token',
    });
    expect(transport).toHaveBeenCalledTimes(3);
  },
);
it('reads the real admin directory', async () => {
  const client = await clientFor('ADMIN');
  transport.mockResolvedValueOnce(
    response([{ id: '32', username: 'worker_test', displayName: '虚构维修' }]),
  );
  expect(await client.workers()).toHaveLength(1);
  expect(transport.mock.calls[2][0]).toBe('/api/v1/admin/workers');
});
it('same account role change discards an older role response', async () => {
  const client = await clientFor('ADMIN');
  let finish!: (r: Response) => void;
  transport.mockImplementationOnce(
    () =>
      new Promise((r) => {
        finish = r;
      }),
  );
  const older = client.detail('101');
  const discarded = expect(older).rejects.toThrow('身份状态已更新');
  transport
    .mockResolvedValueOnce(token())
    .mockResolvedValueOnce(response(identity('WORKER')));
  await client.refresh();
  finish(response({ id: '101', description: 'admin-private' }));
  await discarded;
  transport.mockResolvedValueOnce(response({ items: [] }));
  await client.list();
  expect(transport.mock.calls.at(-1)?.[0]).toBe(
    '/api/v1/worker/orders?page=0&size=20',
  );
});
it.each([
  [401, 'AUTH_REQUIRED'],
  [403, 'ROLE_FORBIDDEN'],
  [404, 'ORDER_NOT_FOUND'],
  [409, 'ORDER_CONFLICT'],
] as const)('never replays a failed action: %s', async (status, code) => {
  const changed = vi.fn();
  const client = await clientFor('WORKER', changed);
  transport.mockResolvedValueOnce(problem(status, code));
  await expect(client.operate('101', 'start')).rejects.toThrow(code);
  expect(transport).toHaveBeenCalledTimes(3);
  if (status === 401) expect(changed).toHaveBeenLastCalledWith(null);
  else expect(changed).toHaveBeenLastCalledWith(identity('WORKER'));
});
it('CSRF rejection freezes actions until explicit identity refresh', async () => {
  const client = await clientFor('ADMIN');
  transport.mockResolvedValueOnce(problem(403, 'CSRF_REJECTED'));
  await expect(
    client.operate('101', 'assign', { workerId: '32' }),
  ).rejects.toThrow('CSRF_REJECTED');
  await expect(
    client.operate('101', 'assign', { workerId: '32' }),
  ).rejects.toThrow('手动提交');
  expect(transport).toHaveBeenCalledTimes(3);
  transport
    .mockResolvedValueOnce(token())
    .mockResolvedValueOnce(response(identity('ADMIN')));
  await client.refresh();
  expect(transport).toHaveBeenCalledTimes(5);
});
it('blocks simultaneous operations and does not mislabel an unknown action as an idempotent create', async () => {
  const client = await clientFor('WORKER');
  let fail!: (e: Error) => void;
  transport.mockImplementationOnce(
    () =>
      new Promise((_, reject) => {
        fail = reject;
      }),
  );
  const first = client.operate('101', 'start');
  const error = expect(first).rejects.toThrow('重新读取工单');
  await expect(client.operate('101', 'start')).rejects.toThrow('勿重复');
  expect(transport).toHaveBeenCalledTimes(3);
  fail(new Error('connection lost'));
  await error;
});
it('rejects traversal and invalid command destinations before sending', async () => {
  const client = await clientFor('ADMIN');
  await expect(
    client.operate('../me', 'assign', { workerId: '32' }),
  ).rejects.toThrow('范围');
  await expect(
    client.operate('101', 'delete' as OrderOperation),
  ).rejects.toThrow('范围');
  expect(transport).toHaveBeenCalledTimes(2);
});
it('reads admin statistics with explicit days window and discards stale role response', async () => {
  const client = await clientFor('ADMIN');
  transport.mockResolvedValueOnce(response({ windowDays: 14, totalOrders: 6 }));
  const first = await client.adminStatistics();
  expect(first.windowDays).toBe(14);
  expect(transport.mock.calls[2][0]).toBe('/api/v1/admin/statistics?days=14');

  transport.mockResolvedValueOnce(response({ windowDays: 30, totalOrders: 6 }));
  const second = await client.adminStatistics(30);
  expect(second.windowDays).toBe(30);
  expect(transport.mock.calls[3][0]).toBe('/api/v1/admin/statistics?days=30');
});
it.each([
  ['STUDENT', '/api/v1/orders/101/attachments'],
  ['ADMIN', '/api/v1/admin/orders/101/attachments'],
  ['WORKER', '/api/v1/worker/orders/101/attachments'],
] as const)(
  'lists order attachments via %s role prefix',
  async (role, expectedPath) => {
    const client = await clientFor(role);
    transport.mockResolvedValueOnce(
      response({ orderId: '101', items: [], canUpload: false }),
    );
    await client.listAttachments('101');
    expect(transport.mock.calls[2][0]).toBe(expectedPath);
  },
);
it('uploads multipart FormData attachment with CSRF token and no JSON Content-Type', async () => {
  const client = await clientFor('STUDENT');
  transport.mockResolvedValueOnce(response({ id: '7', orderId: '101' }, 201));
  const file = new File([new Uint8Array([137, 80, 78, 71])], 'lamp.png', {
    type: 'image/png',
  });
  const created = await client.uploadAttachment('101', file);
  expect(created.id).toBe('7');
  const [url, options] = transport.mock.calls[2];
  expect(url).toBe('/api/v1/orders/101/attachments');
  expect(options?.method).toBe('POST');
  expect(options?.body).toBeInstanceOf(FormData);
  expect(options?.headers).toEqual({
    Accept: 'application/json',
    'X-CSRF-TOKEN': 'test-token',
  });
});
it('routes worker attachment upload to /worker/orders/{id}/attachments and rejects admin upload', async () => {
  const workerClient = await clientFor('WORKER');
  transport.mockResolvedValueOnce(response({ id: '9', orderId: '101' }, 201));
  const file = new File([new Uint8Array([255, 216, 255])], 'done.jpg', {
    type: 'image/jpeg',
  });
  await workerClient.uploadAttachment('101', file);
  expect(transport.mock.calls[2][0]).toBe(
    '/api/v1/worker/orders/101/attachments',
  );

  const adminClient = await clientFor('ADMIN');
  await expect(adminClient.uploadAttachment('101', file)).rejects.toThrow(
    '不支持上传',
  );
  await expect(adminClient.deleteAttachment('101', '9')).rejects.toThrow(
    '仅报修学生',
  );
});
it('deletes student attachment with CSRF token and rejects invalid ids before sending', async () => {
  const client = await clientFor('STUDENT');
  transport.mockResolvedValueOnce(response(null, 204));
  await client.deleteAttachment('101', '12');
  const [url, options] = transport.mock.calls[2];
  expect(url).toBe('/api/v1/orders/101/attachments/12');
  expect(options?.method).toBe('DELETE');
  expect(options?.headers).toEqual({
    Accept: 'application/json',
    'X-CSRF-TOKEN': 'test-token',
  });

  await expect(client.deleteAttachment('101', '../12')).rejects.toThrow('范围');
  expect(transport).toHaveBeenCalledTimes(3);
});
it('reads unread count, lists notifications with unreadOnly filter, and sends CSRF on read actions', async () => {
  const client = await clientFor('STUDENT');
  transport
    .mockResolvedValueOnce(response({ unreadCount: 3 }))
    .mockResolvedValueOnce(
      response({
        items: [],
        page: 1,
        size: 10,
        totalElements: 0,
        totalPages: 0,
        unreadCount: 3,
      }),
    )
    .mockResolvedValueOnce(
      response({
        id: '42',
        read: true,
        readAt: '2026-11-26T15:05:00+08:00',
        unreadCount: 2,
      }),
    )
    .mockResolvedValueOnce(response({ markedCount: 2, unreadCount: 0 }));

  expect((await client.unreadNotificationCount()).unreadCount).toBe(3);
  expect(transport.mock.calls[2][0]).toBe('/api/v1/notifications/unread-count');

  await client.listNotifications(1, true, 10);
  expect(transport.mock.calls[3][0]).toBe(
    '/api/v1/notifications?page=1&size=10&unreadOnly=true',
  );

  const single = await client.markNotificationRead('42');
  expect(single.unreadCount).toBe(2);
  expect(transport.mock.calls[4][0]).toBe('/api/v1/notifications/42/read');
  expect(transport.mock.calls[4][1]?.method).toBe('POST');
  expect(transport.mock.calls[4][1]?.headers).toEqual({
    Accept: 'application/json',
    'Content-Type': 'application/json',
    'X-CSRF-TOKEN': 'test-token',
  });

  const all = await client.markAllNotificationsRead();
  expect(all).toEqual({ markedCount: 2, unreadCount: 0 });
  expect(transport.mock.calls[5][0]).toBe('/api/v1/notifications/read-all');
  expect(transport.mock.calls[5][1]?.method).toBe('POST');

  await expect(client.markNotificationRead('../42')).rejects.toThrow('范围');
  expect(transport).toHaveBeenCalledTimes(6);
});
