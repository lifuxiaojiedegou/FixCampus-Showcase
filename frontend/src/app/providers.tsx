import { useMemo, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DemoContext } from './context';
import { MockRepository } from '../shared/api/mock/mockRepository';
import { users } from '../shared/api/mock/fixtures/users';
import type { RolePath } from '../shared/api/contracts';
import type { Scenario } from '../shared/api/mock/scenarios';

export function DemoProviders({
  children,
  scenario,
  setScenario,
}: {
  children: ReactNode;
  scenario: Scenario;
  setScenario: (s: Scenario) => void;
}) {
  const path = useLocation().pathname.split('/')[1];
  const user = Object.hasOwn(users, path) ? users[path as RolePath] : null;
  const [repository] = useState(() => new MockRepository(scenario));
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { retry: false, staleTime: 0, refetchOnWindowFocus: false },
          mutations: { retry: false },
        },
      }),
  );
  const value = useMemo(
    () => ({ user, repository, scenario, setScenario }),
    [user, repository, scenario, setScenario],
  );
  return (
    <QueryClientProvider client={client}>
      <DemoContext value={value}>{children}</DemoContext>
    </QueryClientProvider>
  );
}
