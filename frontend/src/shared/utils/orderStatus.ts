import type { OrderStatus, RolePath } from '../api/contracts';

export const statuses: Record<OrderStatus, { label: string; tone: string }> = {
  PENDING_ASSIGNMENT: { label: '待分配', tone: 'pending' },
  PENDING_REPAIR: { label: '待维修', tone: 'assigned' },
  IN_PROGRESS: { label: '维修中', tone: 'active' },
  PENDING_CONFIRMATION: { label: '待确认', tone: 'confirmation' },
  COMPLETED: { label: '已完成', tone: 'completed' },
  CANCELLED: { label: '已取消', tone: 'cancelled' },
};
export const roleLabels: Record<RolePath, string> = {
  student: '学生',
  admin: '管理员',
  worker: '维修人员',
};
export const actionLabels = {
  CANCEL: '模拟撤销',
  CONFIRM: '模拟确认完成',
  ASSIGN: '模拟派单',
  START: '模拟开始维修',
  COMPLETE: '模拟提交维修结果',
} as const;
export const historyActionLabels: Record<string, string> = {
  SUBMITTED: '提交报修',
  ASSIGNED: '分配维修人员',
  REPAIR_STARTED: '开始维修',
  REPAIR_COMPLETED: '提交维修结果',
  CONFIRMED: '确认维修完成',
  CANCELLED: '撤销工单',
  COMPLETED: '确认维修完成',
  ASSIGN: '分配维修人员',
  START: '开始维修',
  COMPLETE: '提交维修结果',
  CONFIRM: '确认维修完成',
  CANCEL: '撤销工单',
};
