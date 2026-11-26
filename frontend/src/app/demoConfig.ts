export interface DemoAccountInfo {
  role: 'STUDENT' | 'ADMIN' | 'WORKER';
  roleLabel: string;
  username: string;
  description: string;
}

export interface DemoDisplayConfig {
  enabled: boolean;
  resetNotice: string;
  accessHint: string;
  accounts: readonly DemoAccountInfo[];
}

const DEFAULT_RESET_NOTICE =
  '公开演示环境：演示数据会定期清空，请勿录入真实个人信息或敏感内容。';

const DEMO_ACCOUNTS: readonly DemoAccountInfo[] = [
  {
    role: 'STUDENT',
    roleLabel: '学生',
    username: 'student01',
    description: '提交报修、查看工单进度、撤销待分配工单并确认维修完成。',
  },
  {
    role: 'ADMIN',
    roleLabel: '管理员',
    username: 'admin01',
    description: '查看全量工单、按状态或关键词筛选并派单给维修人员。',
  },
  {
    role: 'WORKER',
    roleLabel: '维修人员',
    username: 'worker01',
    description: '查看分配给本人的维修任务、开始维修并提交维修结果。',
  },
];

export const PROD_DEMO_CONFIG: DemoDisplayConfig = {
  enabled: false,
  resetNotice: '',
  accessHint: '',
  accounts: [],
};

export function getDemoDisplayConfig(
  mode = import.meta.env.MODE,
  env: {
    VITE_DEMO_MODE?: string;
    VITE_DEMO_RESET_NOTICE?: string;
    VITE_DEMO_ACCESS_HINT?: string;
  } = import.meta.env,
): DemoDisplayConfig {
  const enabled = mode === 'demo' || env.VITE_DEMO_MODE === 'true';
  if (!enabled) {
    return PROD_DEMO_CONFIG;
  }
  return {
    enabled: true,
    resetNotice: env.VITE_DEMO_RESET_NOTICE?.trim() || DEFAULT_RESET_NOTICE,
    accessHint: env.VITE_DEMO_ACCESS_HINT?.trim() || '',
    accounts: DEMO_ACCOUNTS,
  };
}
