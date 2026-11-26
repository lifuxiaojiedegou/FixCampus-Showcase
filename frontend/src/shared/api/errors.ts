import type { ProblemDetail } from './contracts';

export class MockProblem extends Error {
  readonly problem: ProblemDetail;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'MockProblem';
    this.problem = {
      type: 'about:blank',
      title: 'Mock 请求失败',
      status,
      code,
      message,
      requestId: 'mock-local-only',
    };
  }
}
export class MockRouteNotFoundError extends MockProblem {
  constructor() {
    super(404, 'MOCK_ROUTE_NOT_FOUND', '未匹配 Mock 路由；请求已拒绝');
  }
}
