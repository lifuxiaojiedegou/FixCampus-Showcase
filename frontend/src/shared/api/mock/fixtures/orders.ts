import type { HistoryEntry, OrderItem } from '../../contracts';

export interface MockOrder extends OrderItem {
  studentId: string;
  workerId: string | null;
  description: string;
  repairResult: string | null;
  completedAt: string | null;
  historyIncomplete: boolean;
  entries: HistoryEntry[];
}
export function makeFixtures(): MockOrder[] {
  const states = [
    'PENDING_ASSIGNMENT',
    'PENDING_REPAIR',
    'IN_PROGRESS',
    'PENDING_CONFIRMATION',
    'COMPLETED',
    'CANCELLED',
  ] as const;
  const locations = [
    '虚构园区 A101',
    '虚构园区 B203',
    '虚构图书室 301',
    '虚构园区 C102',
    '虚构活动室 201',
    '虚构园区 D108',
  ];
  return Array.from({ length: 24 }, (_, i) => {
    const status = states[i % 6];
    const createdAt = `2026-11-${String(7 - Math.floor(i / 6)).padStart(2, '0')}T09:${String(40 - i).padStart(2, '0')}:00+08:00`;
    return {
      id: String(1001 + i),
      location: locations[i % 6],
      category: i % 2 ? 'NETWORK' : 'WATER_ELECTRICITY',
      status,
      studentId: i === 23 ? '4' : '1',
      workerId: ['PENDING_ASSIGNMENT', 'CANCELLED'].includes(status)
        ? null
        : '3',
      description:
        '此为虚构报修：设施出现异常，请协助检查。所有操作仅模拟，不会提交到真实校园系统。',
      repairResult: ['PENDING_CONFIRMATION', 'COMPLETED'].includes(status)
        ? '虚构维修记录：已完成检查并模拟修复。'
        : null,
      createdAt,
      completedAt: status === 'COMPLETED' ? '2026-11-07T12:00:00+08:00' : null,
      // Advanced fixture states have no recorded transition history.
      historyIncomplete: status !== 'PENDING_ASSIGNMENT',
      entries: [
        {
          action: 'SUBMITTED',
          occurredAt: i === 10 ? null : createdAt,
          actorRole: i === 10 ? null : 'STUDENT',
          actorName: i === 10 ? null : '虚构学生小林',
          detail: null,
          recorded: i !== 10,
        },
      ],
    };
  });
}
