export const scenarioLabels = {
  normal: '正常模拟',
  empty: '空列表',
  unavailable: '服务不可用 503',
  forbidden: '角色限制 403',
  expired: '身份失效 401',
  conflict: '操作冲突 409',
} as const;
export type Scenario = keyof typeof scenarioLabels;
