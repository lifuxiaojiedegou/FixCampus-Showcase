import type { MeResponse, RolePath } from '../../contracts';

export const users: Record<RolePath, MeResponse> = {
  student: {
    id: '1',
    username: 'mock-student',
    displayName: '虚构学生小林',
    role: 'STUDENT',
  },
  admin: {
    id: '2',
    username: 'mock-admin',
    displayName: '虚构管理员小周',
    role: 'ADMIN',
  },
  worker: {
    id: '3',
    username: 'mock-worker',
    displayName: '虚构维修员小陈',
    role: 'WORKER',
  },
};
export const otherStudent: MeResponse = {
  id: '4',
  username: 'mock-other',
  displayName: '虚构学生小许',
  role: 'STUDENT',
};
export const workers = [users.worker];
