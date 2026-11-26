import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import App from '../app/App';
import type {
  OrderAttachmentItem,
  OrderAttachmentListResponse,
  OrderStatus,
  Role,
} from '../shared/api/contracts';

const transport = vi.mocked(fetch);

const reply = (body: unknown, status = 200) =>
  new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: {
      'Content-Type':
        status >= 400 ? 'application/problem+json' : 'application/json',
    },
  });

const problem = (status: number, code: string, message = code) =>
  reply({ status, code, message, requestId: `att-${status}` }, status);

const DEFAULT_LIMITS = {
  maxFileBytes: 2 * 1024 * 1024,
  maxWidth: 4096,
  maxHeight: 4096,
  maxPixels: 16000000,
  maxStudentAttachments: 3,
  maxWorkerAttachments: 2,
  allowedContentTypes: ['image/png', 'image/jpeg'] as (
    'image/png' | 'image/jpeg'
  )[],
};

let role: Role;
let orderStatus: OrderStatus;
let items: OrderAttachmentItem[];
let uploadFailure:
  { status: number; code: string; message: string } | 'NETWORK' | null;
let listFailure: { status: number; code: string; message: string } | null;
let failNextListRefresh: {
  status: number;
  code: string;
  message: string;
} | null;
let holdNextList: ((res: Response) => void) | null;
let holdListOnce: boolean;

function prefixFor(r: Role) {
  return r === 'ADMIN'
    ? '/api/v1/admin/orders'
    : r === 'WORKER'
      ? '/api/v1/worker/orders'
      : '/api/v1/orders';
}

function makeAttachment(
  id: string,
  uploaderRole: 'STUDENT' | 'WORKER',
  filename: string,
): OrderAttachmentItem {
  return {
    id,
    orderId: '101',
    uploaderRole,
    uploaderName: uploaderRole === 'WORKER' ? '演示维修员' : '演示学生',
    originalFilename: filename,
    contentType: filename.endsWith('.jpg') ? 'image/jpeg' : 'image/png',
    sizeBytes: 42500,
    width: 800,
    height: 600,
    createdAt: '2026-11-26T14:30:00+08:00',
    contentUrl: `${prefixFor(role)}/101/attachments/${id}/content`,
    canDelete:
      role === 'STUDENT' &&
      orderStatus === 'PENDING_ASSIGNMENT' &&
      uploaderRole === 'STUDENT',
  };
}

function listResponse(): OrderAttachmentListResponse {
  const studentCount = items.filter((i) => i.uploaderRole === 'STUDENT').length;
  const workerCount = items.filter((i) => i.uploaderRole === 'WORKER').length;
  const canUpload =
    (role === 'STUDENT' &&
      (orderStatus === 'PENDING_ASSIGNMENT' ||
        orderStatus === 'PENDING_REPAIR') &&
      studentCount < DEFAULT_LIMITS.maxStudentAttachments) ||
    (role === 'WORKER' &&
      orderStatus === 'IN_PROGRESS' &&
      workerCount < DEFAULT_LIMITS.maxWorkerAttachments);
  return {
    orderId: '101',
    items: items.map((item) => ({
      ...item,
      contentUrl: `${prefixFor(role)}/101/attachments/${item.id}/content`,
      canDelete:
        role === 'STUDENT' &&
        orderStatus === 'PENDING_ASSIGNMENT' &&
        item.uploaderRole === 'STUDENT',
    })),
    limits: DEFAULT_LIMITS,
    canUpload,
  };
}

beforeEach(() => {
  role = 'STUDENT';
  orderStatus = 'PENDING_ASSIGNMENT';
  items = [];
  uploadFailure = null;
  listFailure = null;
  failNextListRefresh = null;
  holdNextList = null;
  holdListOnce = false;

  transport.mockReset().mockImplementation(async (input, options) => {
    const u = new URL(String(input), 'https://local.invalid');
    const method = options?.method ?? 'GET';
    if (u.pathname === '/api/v1/auth/csrf') {
      return reply({ headerName: 'X-CSRF-TOKEN', token: 'att-csrf' });
    }
    if (u.pathname === '/api/v1/me') {
      return reply({
        id: '10',
        username: `${role.toLowerCase()}01`,
        displayName: `演示${role}`,
        role,
      });
    }
    if (u.pathname === '/api/v1/notifications/unread-count') {
      return reply({ unreadCount: 0 });
    }
    if (u.pathname === `${prefixFor(role)}/101`) {
      return reply({
        id: '101',
        location: '宿舍 4 栋 302',
        category: 'WATER_ELECTRICITY',
        description: '阳台照明灯不亮',
        status: orderStatus,
        createdAt: '2026-11-26T10:00:00+08:00',
        repairResult: null,
        completedAt: null,
        availableActions:
          role === 'WORKER' && orderStatus === 'IN_PROGRESS'
            ? ['COMPLETE']
            : [],
        history: { incomplete: false, entries: [] },
      });
    }
    if (method === 'POST' && u.pathname === '/api/v1/orders/101/complete') {
      orderStatus = 'PENDING_CONFIRMATION';
      return reply(null, 204);
    }
    if (
      method === 'GET' &&
      u.pathname === `${prefixFor(role)}/101/attachments`
    ) {
      if (holdListOnce) {
        holdListOnce = false;
        return new Promise((resolve) => {
          holdNextList = resolve;
        });
      }
      if (failNextListRefresh) {
        const next = failNextListRefresh;
        failNextListRefresh = null;
        return problem(next.status, next.code, next.message);
      }
      if (listFailure) {
        return problem(
          listFailure.status,
          listFailure.code,
          listFailure.message,
        );
      }
      return reply(listResponse());
    }
    if (
      method === 'POST' &&
      (u.pathname === '/api/v1/orders/101/attachments' ||
        u.pathname === '/api/v1/worker/orders/101/attachments')
    ) {
      if (uploadFailure === 'NETWORK') {
        throw new TypeError('Failed to send');
      }
      if (uploadFailure) {
        return problem(
          uploadFailure.status,
          uploadFailure.code,
          uploadFailure.message,
        );
      }
      const uploaded = makeAttachment(
        String(items.length + 1),
        role === 'WORKER' ? 'WORKER' : 'STUDENT',
        role === 'WORKER' ? 'fixed.jpg' : 'broken.png',
      );
      items = [...items, uploaded];
      return reply(uploaded, 201);
    }
    if (
      method === 'DELETE' &&
      u.pathname.startsWith('/api/v1/orders/101/attachments/')
    ) {
      const id = u.pathname.split('/').pop()!;
      items = items.filter((item) => item.id !== id);
      return reply(null, 204);
    }
    throw new Error(`Unexpected test request: ${method} ${u.pathname}`);
  });
});

function mount() {
  render(
    <MemoryRouter initialEntries={['/orders/101']}>
      <App />
    </MemoryRouter>,
  );
}

it('student uploads a PNG, previews full image, restores focus on Escape, handles broken image, and deletes attachment', async () => {
  mount();
  const user = userEvent.setup();
  expect(await screen.findByText('暂无图片附件')).toBeInTheDocument();

  const fileInput = screen.getByLabelText(
    /上传报修现场照片/,
  ) as HTMLInputElement;
  const pngFile = new File([new Uint8Array([137, 80, 78, 71])], 'broken.png', {
    type: 'image/png',
  });
  await user.upload(fileInput, pngFile);
  await user.click(screen.getByRole('button', { name: '上传图片' }));

  expect(
    await screen.findByText('图片已上传并更新附件列表。'),
  ).toBeInTheDocument();
  expect(screen.getByText('broken.png')).toBeInTheDocument();
  const thumb = screen.getByRole('img', { name: /报修现场图：broken\.png/ });
  expect(thumb).toHaveAttribute(
    'src',
    '/api/v1/orders/101/attachments/1/content',
  );

  const openBtn = screen.getByRole('button', { name: '查看大图' });
  await user.click(openBtn);
  const region = await screen.findByRole('region', {
    name: /附件大图预览：broken\.png/,
  });
  expect(region).toBeInTheDocument();

  // Trigger image load failure feedback on full preview
  const fullImg = screen.getByRole('img', { name: /大图预览：broken\.png/ });
  fireEvent.error(fullImg);
  expect(
    await screen.findByText(/大图加载失败，请点击「刷新附件」/),
  ).toBeInTheDocument();

  fireEvent.keyDown(region, { key: 'Escape' });
  expect(
    screen.queryByRole('region', { name: /附件大图预览/ }),
  ).not.toBeInTheDocument();
  expect(document.activeElement).toBe(openBtn);

  await user.click(screen.getByRole('button', { name: '删除附件' }));
  await user.click(screen.getByRole('button', { name: '确认删除' }));
  expect(await screen.findByText('附件已删除。')).toBeInTheDocument();
  expect(screen.getByText('暂无图片附件')).toBeInTheDocument();
});

it('rejects unsupported file type and oversized file client-side without sending request', async () => {
  mount();
  const user = userEvent.setup();
  expect(await screen.findByText('暂无图片附件')).toBeInTheDocument();
  const callsBefore = transport.mock.calls.length;

  const fileInput = screen.getByLabelText(
    /上传报修现场照片/,
  ) as HTMLInputElement;
  const svgFile = new File(['<svg></svg>'], 'evil.svg', {
    type: 'image/svg+xml',
  });
  fireEvent.change(fileInput, { target: { files: [svgFile] } });
  await user.click(screen.getByRole('button', { name: '上传图片' }));
  expect(await screen.findByText(/仅支持 PNG 或 JPEG/)).toBeInTheDocument();
  expect(transport).toHaveBeenCalledTimes(callsBefore);

  const bigFile = new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'huge.png', {
    type: 'image/png',
  });
  fireEvent.change(fileInput, { target: { files: [bigFile] } });
  await user.click(screen.getByRole('button', { name: '上传图片' }));
  expect(await screen.findByText(/图片大小不能超过/)).toBeInTheDocument();
  expect(transport).toHaveBeenCalledTimes(callsBefore);
});

it('clears selected file on reload and updates local state immediately if post-upload or post-delete refresh fails', async () => {
  mount();
  const user = userEvent.setup();
  expect(await screen.findByText('暂无图片附件')).toBeInTheDocument();

  const fileInput = screen.getByLabelText(
    /上传报修现场照片/,
  ) as HTMLInputElement;
  const pngFile = new File([new Uint8Array([137, 80, 78, 71])], 'first.png', {
    type: 'image/png',
  });
  await user.upload(fileInput, pngFile);
  expect(screen.getByRole('button', { name: '上传图片' })).toBeEnabled();

  // Reloading clears stale selected file so blank input cannot resubmit old File
  await user.click(screen.getByRole('button', { name: '刷新附件' }));
  expect(await screen.findByText('暂无图片附件')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '上传图片' })).toBeDisabled();

  // Upload 201 succeeds, subsequent list refresh returns 503
  const nextInput = screen.getByLabelText(
    /上传报修现场照片/,
  ) as HTMLInputElement;
  await user.upload(nextInput, pngFile);
  failNextListRefresh = {
    status: 503,
    code: 'TEMPORARILY_UNAVAILABLE',
    message: '暂时不可用',
  };
  await user.click(screen.getByRole('button', { name: '上传图片' }));

  expect(
    await screen.findByText('图片已上传并更新附件列表。'),
  ).toBeInTheDocument();
  expect(screen.getByText(/重新读取附件列表失败/)).toBeInTheDocument();
  expect(screen.getByText('broken.png')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '上传图片' })).toBeDisabled();

  // Delete 204 succeeds, subsequent list refresh fails -> card is removed immediately
  failNextListRefresh = {
    status: 503,
    code: 'TEMPORARILY_UNAVAILABLE',
    message: '暂时不可用',
  };
  await user.click(screen.getByRole('button', { name: '删除附件' }));
  await user.click(screen.getByRole('button', { name: '确认删除' }));
  expect(await screen.findByText('附件已删除。')).toBeInTheDocument();
  expect(screen.queryByText('broken.png')).not.toBeInTheDocument();
  expect(screen.getByText(/重新读取附件列表失败/)).toBeInTheDocument();
});

it('blocks retries after unknown network upload outcome until explicit reload', async () => {
  uploadFailure = 'NETWORK';
  mount();
  const user = userEvent.setup();
  expect(await screen.findByText('暂无图片附件')).toBeInTheDocument();

  const fileInput = screen.getByLabelText(
    /上传报修现场照片/,
  ) as HTMLInputElement;
  const pngFile = new File([new Uint8Array([137, 80, 78, 71])], 'net.png', {
    type: 'image/png',
  });
  await user.upload(fileInput, pngFile);
  await user.click(screen.getByRole('button', { name: '上传图片' }));

  expect(
    await screen.findByText(/附件提交结果未知，未自动重试/),
  ).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '上传图片' })).toBeDisabled();

  uploadFailure = null;
  await user.click(screen.getByRole('button', { name: '刷新附件' }));
  await waitFor(() => {
    expect(
      screen.queryByText(/附件提交结果未知，未自动重试/),
    ).not.toBeInTheDocument();
  });
});

it('worker uploads completion photo during IN_PROGRESS and stale list response cannot reopen upload after COMPLETE', async () => {
  role = 'WORKER';
  orderStatus = 'IN_PROGRESS';
  items = [makeAttachment('1', 'STUDENT', 'issue.png')];
  mount();
  const user = userEvent.setup();

  expect(await screen.findByText('issue.png')).toBeInTheDocument();
  expect(
    screen.queryByRole('button', { name: '删除附件' }),
  ).not.toBeInTheDocument();

  const fileInput = screen.getByLabelText(
    /上传维修结果照片/,
  ) as HTMLInputElement;
  const jpgFile = new File([new Uint8Array([255, 216, 255])], 'fixed.jpg', {
    type: 'image/jpeg',
  });
  await user.upload(fileInput, jpgFile);
  holdListOnce = true;
  await user.click(screen.getByRole('button', { name: '上传图片' }));
  expect(await screen.findByText('fixed.jpg')).toBeInTheDocument();
  const staleListResolve = holdNextList!;

  // Worker completes the order while the post-upload list refresh is still in flight
  await user.click(screen.getByRole('button', { name: '提交维修结果' }));
  expect(
    await screen.findByText('操作完成，已读取最新工单。'),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole('form', { name: '上传图片附件' }),
  ).not.toBeInTheDocument();

  // Stale response claiming canUpload=true arrives late and must be discarded
  staleListResolve(
    reply({
      orderId: '101',
      items: [makeAttachment('1', 'STUDENT', 'issue.png')],
      limits: DEFAULT_LIMITS,
      canUpload: true,
    }),
  );
  await waitFor(() => {
    expect(
      screen.queryByRole('form', { name: '上传图片附件' }),
    ).not.toBeInTheDocument();
  });
});

it('admin views attachments read-only without upload or delete controls', async () => {
  role = 'ADMIN';
  orderStatus = 'COMPLETED';
  items = [
    makeAttachment('1', 'STUDENT', 'before.png'),
    makeAttachment('2', 'WORKER', 'after.jpg'),
  ];
  mount();

  expect(await screen.findByText('before.png')).toBeInTheDocument();
  expect(screen.getByText('after.jpg')).toBeInTheDocument();
  expect(
    screen.queryByRole('form', { name: '上传图片附件' }),
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole('button', { name: '删除附件' }),
  ).not.toBeInTheDocument();
  expect(
    screen.getByRole('img', { name: /报修现场图：before\.png/ }),
  ).toHaveAttribute('src', '/api/v1/admin/orders/101/attachments/1/content');
});

it('surfaces 413/409 upload errors and clears rendered cards and open preview on 403/404 reload', async () => {
  items = [makeAttachment('1', 'STUDENT', 'existing.png')];
  uploadFailure = {
    status: 413,
    code: 'PAYLOAD_TOO_LARGE',
    message: '图片超过 2 MiB 限制',
  };
  mount();
  const user = userEvent.setup();
  expect(await screen.findByText('existing.png')).toBeInTheDocument();

  const fileInput = screen.getByLabelText(
    /上传报修现场照片/,
  ) as HTMLInputElement;
  const pngFile = new File([new Uint8Array([137, 80, 78, 71])], 'ok.png', {
    type: 'image/png',
  });
  await user.upload(fileInput, pngFile);
  await user.click(screen.getByRole('button', { name: '上传图片' }));
  expect(await screen.findByText(/PAYLOAD_TOO_LARGE/)).toBeInTheDocument();
  expect(screen.getByText(/att-413/)).toBeInTheDocument();

  uploadFailure = {
    status: 409,
    code: 'ATTACHMENT_LIMIT_EXCEEDED',
    message: '附件数量已达上限',
  };
  await user.click(screen.getByRole('button', { name: '上传图片' }));
  expect(
    await screen.findByText(/ATTACHMENT_LIMIT_EXCEEDED/),
  ).toBeInTheDocument();
  expect(
    screen.getByText(/工单或附件状态已变化，已暂停继续提交/),
  ).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '上传图片' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '删除附件' })).toBeDisabled();

  // Reloading attachments clears the 409 state lock and re-enables actions
  uploadFailure = null;
  await user.click(screen.getByRole('button', { name: '刷新附件' }));
  await waitFor(() =>
    expect(
      screen.queryByText(/工单或附件状态已变化，已暂停继续提交/),
    ).not.toBeInTheDocument(),
  );
  expect(screen.getByRole('button', { name: '删除附件' })).toBeEnabled();

  // Quota-exceeded 409 shows dedicated quota recovery guidance and locks upload
  const quotaInput = screen.getByLabelText(
    /上传报修现场照片/,
  ) as HTMLInputElement;
  await user.upload(quotaInput, pngFile);
  uploadFailure = {
    status: 409,
    code: 'ATTACHMENT_QUOTA_EXCEEDED',
    message: '附件存储空间已达上限',
  };
  await user.click(screen.getByRole('button', { name: '上传图片' }));
  expect(await screen.findByText(/系统附件存储总配额已满/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '上传图片' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '删除附件' })).toBeEnabled();

  // Open full preview, then reload hitting 403 -> both card and open preview must disappear
  await user.click(screen.getByRole('button', { name: '查看大图' }));
  expect(
    await screen.findByRole('region', { name: /附件大图预览：existing\.png/ }),
  ).toBeInTheDocument();

  listFailure = {
    status: 403,
    code: 'ROLE_FORBIDDEN',
    message: '请求被拒绝',
  };
  await user.click(screen.getByRole('button', { name: '刷新附件' }));
  await waitFor(() => {
    expect(screen.getByText(/ROLE_FORBIDDEN/)).toBeInTheDocument();
  });
  expect(screen.queryByText('existing.png')).not.toBeInTheDocument();
  expect(
    screen.queryByRole('region', { name: /附件大图预览/ }),
  ).not.toBeInTheDocument();

  // Subsequent 404 reload displays ORDER_NOT_FOUND cleanly
  listFailure = {
    status: 404,
    code: 'ORDER_NOT_FOUND',
    message: '工单不存在或无权访问',
  };
  await user.click(screen.getByRole('button', { name: '刷新附件' }));
  await waitFor(() => {
    expect(screen.getByText(/ORDER_NOT_FOUND/)).toBeInTheDocument();
  });
});
