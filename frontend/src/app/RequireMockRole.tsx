import { Navigate, Outlet } from 'react-router-dom';
import { useDemo } from './context';
import type { Role } from '../shared/api/contracts';

// URL-derived virtual identity is an explicit demo choice, never authentication.
export function RequireMockRole({ role }: { role: Role }) {
  const { user } = useDemo();
  return user?.role === role ? <Outlet /> : <Navigate to="/" replace />;
}
