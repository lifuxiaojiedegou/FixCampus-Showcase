import { beforeEach, expect, it, vi } from 'vitest';
import { realError, StudentClient } from '../shared/api/rest/studentClient';

const transport = vi.mocked(fetch);
const identity = {
  id: '21',
  username: 'student_test',
  displayName: '虚构学生',
  role: 'STUDENT',
};
const input = {
  location: '虚构 A101',
  category: 'OTHER' as const,
  description: '测试',
};
const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type':
        status < 400 ? 'application/json' : 'application/problem+json',
    },
  });
const csrf = () => response({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' });
const problem = (status: number, code: string) =>
  response(
    {
      type: 'about:blank',
      title: code,
      status,
      code,
      message: code,
      requestId: 'test',
    },
    status,
  );
beforeEach(() => {
  transport.mockReset();
});
async function authenticated() {
  const client = new StudentClient();
  transport
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(response(identity));
  await client.refresh();
  return client;
}
it('uses only same-origin relative API requests and never persists credentials', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem');
  const client = await authenticated();
  transport.mockResolvedValueOnce(
    response({ id: '101', status: 'PENDING_ASSIGNMENT' }, 201),
  );
  await client.create(input);
  for (const [url, options] of transport.mock.calls) {
    expect(url).toMatch(/^\/api\/v1\//);
    expect(options?.credentials).toBe('same-origin');
    expect(options?.redirect).toBe('error');
  }
  expect(storage).not.toHaveBeenCalled();
  storage.mockRestore();
});
it.each(['headers', 'body'] as const)(
  'retains the original key and payload after response %s loss, without automatic retry',
  async (phase) => {
    const client = await authenticated();
    if (phase === 'headers') {
      transport.mockRejectedValueOnce(new TypeError('response lost'));
    } else {
      const truncated = new ReadableStream({
        start(controller) {
          controller.error(new TypeError('response body interrupted'));
        },
      });
      transport.mockResolvedValueOnce(
        new Response(truncated, {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    }
    await expect(client.create(input)).rejects.toThrow('原提交键已保留');
    expect(transport).toHaveBeenCalledTimes(3);
    const first = transport.mock.calls[2][1];
    transport.mockResolvedValueOnce(
      response({ id: '101', status: 'PENDING_ASSIGNMENT' }, 201),
    );
    await client.create(input);
    expect(transport.mock.calls[3][1]?.headers).toEqual(first?.headers);
    expect(transport.mock.calls[3][1]?.body).toBe(first?.body);
  },
);
it('rejects writes before transport when offline and does not replay on reconnect', async () => {
  const client = await authenticated();
  const online = vi.spyOn(Navigator.prototype, 'onLine', 'get');
  try {
    online.mockReturnValue(false);
    await expect(client.create(input)).rejects.toThrow('本次请求未发送');
    await expect(client.operate('101', 'cancel')).rejects.toThrow(
      '本次请求未发送',
    );
    await expect(client.logout()).rejects.toThrow('本次请求未发送');
    expect(client.pendingInput()).toBeUndefined();
    expect(transport).toHaveBeenCalledTimes(2);

    online.mockReturnValue(true);
    window.dispatchEvent(new Event('online'));
    expect(transport).toHaveBeenCalledTimes(2);
    transport.mockResolvedValueOnce(
      response({ id: '101', status: 'PENDING_ASSIGNMENT' }, 201),
    );
    await expect(client.create(input)).resolves.toMatchObject({ id: '101' });
    expect(transport).toHaveBeenCalledTimes(3);
  } finally {
    online.mockRestore();
  }
});
it('does not issue a new key for changed content after an unresolved submission', async () => {
  const client = await authenticated();
  transport.mockResolvedValueOnce(problem(503, 'TEMPORARILY_UNAVAILABLE'));
  await expect(client.create(input)).rejects.toThrow();
  await expect(
    client.create({ ...input, description: 'changed' }),
  ).rejects.toThrow('原身份和原内容');
  expect(transport).toHaveBeenCalledTimes(3);
});
it('CSRF failure freezes writes until explicit refresh and never replays the write', async () => {
  const client = await authenticated();
  transport.mockResolvedValueOnce(problem(403, 'CSRF_REJECTED'));
  await expect(client.create(input)).rejects.toThrow('CSRF_REJECTED');
  await expect(client.create(input)).rejects.toThrow('手动提交');
  expect(transport).toHaveBeenCalledTimes(3);
  transport
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(response(identity));
  await client.refresh();
  expect(transport).toHaveBeenCalledTimes(5);
});
it('refreshes CSRF after login and reads identity from the server', async () => {
  const client = new StudentClient();
  transport
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(response(identity))
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(response(identity));
  expect(await client.login('student_test', 'test-only')).toEqual(identity);
  expect(transport.mock.calls.map((c) => c[0])).toEqual([
    '/api/v1/auth/csrf',
    '/api/v1/auth/login',
    '/api/v1/auth/csrf',
    '/api/v1/me',
  ]);
});
it('rejects external, traversal and nondecimal detail destinations before transport', async () => {
  const client = new StudentClient();
  await expect(client.detail('https://example.invalid')).rejects.toThrow(
    '范围',
  );
  await expect(client.detail('../me')).rejects.toThrow('范围');
  expect(transport).not.toHaveBeenCalled();
});
it('revokes identity on 401 and discards an older successful detail response', async () => {
  const changed = vi.fn();
  const client = new StudentClient(changed);
  transport
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(response(identity));
  await client.refresh();
  let finish!: (value: Response) => void;
  transport.mockImplementationOnce(
    () =>
      new Promise<Response>((resolve) => {
        finish = resolve;
      }),
  );
  const staleDetail = client.detail('101');
  const rejectedDetail = expect(staleDetail).rejects.toThrow('身份状态已更新');
  transport.mockResolvedValueOnce(problem(401, 'AUTH_REQUIRED'));
  await expect(client.list()).rejects.toThrow('AUTH_REQUIRED');
  expect(changed).toHaveBeenLastCalledWith(null);
  finish(response({ id: '101', description: '旧身份敏感内容' }));
  await rejectedDetail;
  await expect(client.create(input)).rejects.toThrow('请先确认登录身份');
});
it('preserves unresolved key and body across expiry and refuses recovery as another identity', async () => {
  const client = await authenticated();
  transport.mockResolvedValueOnce(problem(401, 'AUTH_REQUIRED'));
  await expect(client.create(input)).rejects.toThrow('AUTH_REQUIRED');
  const original = transport.mock.calls[2][1];
  expect(client.pendingInput()).toBeUndefined();
  transport
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(response({ ...identity, id: '22' }));
  await client.refresh();
  expect(client.pendingInput()).toBeUndefined();
  const before = transport.mock.calls.length;
  await expect(client.create(input)).rejects.toThrow('原身份和原内容');
  expect(transport).toHaveBeenCalledTimes(before);
  transport
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(response(identity));
  await client.refresh();
  expect(client.pendingInput()).toEqual(input);
  transport.mockResolvedValueOnce(
    response({ id: '101', status: 'PENDING_ASSIGNMENT' }, 201),
  );
  await client.create(input);
  const recovered = transport.mock.calls.at(-1)![1];
  expect(
    (recovered?.headers as Record<string, string>)['Idempotency-Key'],
  ).toBe((original?.headers as Record<string, string>)['Idempotency-Key']);
  expect(recovered?.body).toBe(original?.body);
});
it('role 403 preserves identity and CSRF whereas CSRF 403 only freezes writes', async () => {
  const client = await authenticated();
  transport.mockResolvedValueOnce(problem(403, 'ROLE_FORBIDDEN'));
  await expect(client.create(input)).rejects.toThrow('ROLE_FORBIDDEN');
  transport.mockResolvedValueOnce(
    response({ id: '101', status: 'PENDING_ASSIGNMENT' }, 201),
  );
  await expect(client.create(input)).resolves.toEqual({
    id: '101',
    status: 'PENDING_ASSIGNMENT',
  });
  expect(transport).toHaveBeenCalledTimes(4);
});
it('retains create Idempotency-Key on 429 RATE_LIMITED without automatic retry', async () => {
  const client = await authenticated();
  transport.mockResolvedValueOnce(problem(429, 'RATE_LIMITED'));
  try {
    await client.create(input);
    expect.unreachable('expected 429 error');
  } catch (err) {
    expect(realError(err)).toContain('429 · RATE_LIMITED');
    expect(realError(err)).toContain('请求过于频繁');
  }
  const first = transport.mock.calls[2][1];
  expect(transport).toHaveBeenCalledTimes(3);
  transport.mockResolvedValueOnce(
    response({ id: '101', status: 'PENDING_ASSIGNMENT' }, 201),
  );
  await client.create(input);
  expect(
    (transport.mock.calls[3][1]?.headers as Record<string, string>)[
      'Idempotency-Key'
    ],
  ).toBe((first?.headers as Record<string, string>)['Idempotency-Key']);
});
