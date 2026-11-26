import { expect, it, vi } from 'vitest';
import { MockRepository } from '../shared/api/mock/mockRepository';
import { users } from '../shared/api/mock/fixtures/users';

it('runs reads and writes without invoking transport or storing identity', async () => {
  const local = vi.spyOn(Storage.prototype, 'setItem');
  const cookie = vi.spyOn(Document.prototype, 'cookie', 'set');
  const transport = vi.mocked(globalThis.fetch);
  const before = transport.mock.calls.length;
  const repo = new MockRepository('normal', 0);
  const created = await repo.createOrder(users.student, {
    location: '虚构',
    category: 'OTHER',
    description: '虚构测试',
  });
  await repo.getOrder(users.student, created.id);
  await repo.updateOrder(users.student, created.id, 'CANCEL');
  expect(transport.mock.calls.length).toBe(before);
  expect(local).not.toHaveBeenCalled();
  expect(cookie).not.toHaveBeenCalled();
  local.mockRestore();
  cookie.mockRestore();
});
