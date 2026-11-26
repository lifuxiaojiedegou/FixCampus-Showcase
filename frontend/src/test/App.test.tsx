import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { MockApp } from '../app/MockApp';

function renderPage(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <MockApp />
    </MemoryRouter>,
  );
}
describe('UI-only role pages', () => {
  it('keeps demo controls collapsed until requested and exposes only implemented navigation', async () => {
    const user = userEvent.setup();
    renderPage('/student/orders');
    const summary = screen.getByText('模拟场景设置');
    const details = summary.closest('details');
    expect(details).not.toHaveAttribute('open');
    await user.click(summary);
    expect(details).toHaveAttribute('open');
    expect(screen.getByLabelText('演示场景')).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: '角色导航' });
    expect(within(nav).getAllByRole('link')).toHaveLength(2);
    expect(
      within(nav).queryByRole('link', { name: /通知|统计|评分/ }),
    ).not.toBeInTheDocument();
  });
  it('does not reuse navigation success after refresh resets the mock order', async () => {
    render(
      <MemoryRouter
        initialEntries={[
          {
            pathname: '/student/orders/1025',
            state: { notice: '模拟提交成功，仅保存在内存中；刷新后消失。' },
          },
        ]}
      >
        <MockApp />
      </MemoryRouter>,
    );
    await screen.findByText('404 · ORDER_NOT_FOUND');
    expect(screen.queryByText(/模拟提交成功/)).not.toBeInTheDocument();
  });
  it('exposes all three virtual identity entries and disclaimer', () => {
    renderPage('/');
    expect(
      screen.getByRole('heading', { name: '选择一个虚构身份' }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /进入.*演示/ })).toHaveLength(3);
    expect(screen.getByText(/操作仅存内存/)).toBeInTheDocument();
  });
  it.each([
    ['student', '我的报修'],
    ['admin', '工单管理'],
    ['worker', '我的维修任务'],
  ])('restores %s virtual identity from a direct URL', async (role, title) => {
    renderPage(`/${role}/orders`);
    expect(screen.getByRole('heading', { name: title })).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { name: '模拟工单列表' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '工单工作台' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByRole('main')).toHaveFocus();
  });
  it('filters, resets, paginates and shows empty state', async () => {
    const user = userEvent.setup();
    renderPage('/student/orders');
    await screen.findByRole('heading', { name: '模拟工单列表' });
    await user.click(screen.getByRole('button', { name: '下一页' }));
    await screen.findByText('第 2 页 / 共 2 页');
    await user.type(screen.getByLabelText('地点或工单编号'), '无匹配');
    await user.click(screen.getByRole('button', { name: '筛选' }));
    await screen.findByRole('heading', { name: '暂无模拟工单' });
    await user.click(screen.getByRole('button', { name: '重置' }));
    await screen.findByRole('link', { name: '查看模拟工单 1001' });
  });
  it.each([
    ['empty', '暂无模拟工单'],
    ['unavailable', '503'],
    ['forbidden', '403'],
    ['expired', '401'],
  ])('renders %s without network', async (scenario, text) => {
    const user = userEvent.setup();
    renderPage('/student/orders');
    await user.click(screen.getByText('模拟场景设置'));
    await user.selectOptions(screen.getByLabelText('演示场景'), scenario);
    expect(await screen.findByText(new RegExp(text))).toBeInTheDocument();
  });
  it('validates accessible form fields and creates only a virtual order', async () => {
    const user = userEvent.setup();
    renderPage('/student/new');
    await user.click(screen.getByRole('button', { name: '模拟提交报修' }));
    const location = screen.getByLabelText('报修地点');
    expect(location).toHaveAttribute('aria-invalid', 'true');
    expect(location).toHaveAttribute(
      'aria-describedby',
      'location-hint location-error',
    );
    await user.type(location, '虚构测试地点');
    await user.selectOptions(screen.getByLabelText('故障类型'), 'NETWORK');
    await user.type(screen.getByLabelText('问题描述'), '虚构网络故障');
    await user.click(screen.getByRole('button', { name: '模拟提交报修' }));
    expect(
      await screen.findByRole('heading', { name: '模拟工单 #1025' }),
    ).toBeInTheDocument();
    expect(await screen.findByText('虚构测试地点')).toBeInTheDocument();
    expect(screen.getByText(/模拟提交成功/)).toBeInTheDocument();
  });
  it('shows 409 without a success message or automatic replay', async () => {
    const user = userEvent.setup();
    renderPage('/student/orders/1001');
    await screen.findByRole('button', { name: '模拟撤销' });
    await user.click(screen.getByText('模拟场景设置'));
    await user.selectOptions(screen.getByLabelText('演示场景'), 'conflict');
    await user.click(await screen.findByRole('button', { name: '模拟撤销' }));
    expect(
      await screen.findByText('409 · ORDER_STATE_CONFLICT'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/模拟操作成功/)).not.toBeInTheDocument();
  });
  it('supports administrator assignment with required worker selection', async () => {
    const user = userEvent.setup();
    renderPage('/admin/orders/1001');
    await screen.findByLabelText('虚构维修人员');
    expect(
      within(screen.getByLabelText('虚构维修人员'))
        .getAllByRole('option')
        .map((option) => (option as HTMLOptionElement).value),
    ).toEqual(['', '3']);
    await user.click(screen.getByRole('button', { name: '模拟派单' }));
    expect(await screen.findByText('请选择有效的维修人员')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('虚构维修人员'), '3');
    await user.click(screen.getByRole('button', { name: '模拟派单' }));
    expect(
      await screen.findByText(
        '模拟操作成功，仅更新当前内存；刷新会重置，未写入真实系统。',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('待维修')).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: '切换虚构身份' }));
    await user.click(screen.getByRole('link', { name: /进入维修人员演示/ }));
    await user.click(
      await screen.findByRole('link', { name: '查看模拟工单 1001' }),
    );
    await user.click(
      await screen.findByRole('button', { name: '模拟开始维修' }),
    );
    const result = await screen.findByLabelText('维修说明（可选）');
    await user.type(result, '虚构维修员3处理派单');
    await user.click(screen.getByRole('button', { name: '模拟提交维修结果' }));
    expect(await screen.findByText('待确认')).toBeInTheDocument();
  });
  it('supports worker completion and preserves no early completion date', async () => {
    const user = userEvent.setup();
    renderPage('/worker/orders/1003');
    const field = await screen.findByLabelText('维修说明（可选）');
    await user.type(field, '虚构维修完成');
    await user.click(screen.getByRole('button', { name: '模拟提交维修结果' }));
    expect(await screen.findByText('待确认')).toBeInTheDocument();
    const dl = screen.getByText('确认完成时间').parentElement!;
    expect(within(dl).getByText('未记录')).toBeInTheDocument();
  });
  it('keeps private objects indistinguishable from missing objects', async () => {
    renderPage('/student/orders/1024');
    expect(
      await screen.findByText('404 · ORDER_NOT_FOUND'),
    ).toBeInTheDocument();
  });
  it('shows incomplete history without inventing an actor', async () => {
    renderPage('/admin/orders/1011');
    expect(await screen.findByText('历史事件未记录')).toBeInTheDocument();
    expect(screen.getByText('操作者未记录')).toBeInTheDocument();
  });
  it('does not leak role pages for unknown routes', () => {
    renderPage('/student/admin/orders');
    expect(
      screen.getByRole('heading', { name: '404 · 未找到此演示页面' }),
    ).toBeInTheDocument();
  });
  it('supports keyboard entry and skip link', async () => {
    const user = userEvent.setup();
    renderPage('/');
    screen.getByRole('link', { name: '跳到主要内容' }).focus();
    await user.keyboard('{Enter}');
    expect(screen.getByRole('link', { name: '跳到主要内容' })).toHaveAttribute(
      'href',
      '#main',
    );
  });
});
