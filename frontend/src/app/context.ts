import { createContext, useContext } from 'react';
import type { MeResponse } from '../shared/api/contracts';
import type { OrderRepository } from '../shared/api/orderRepository';
import type { Scenario } from '../shared/api/mock/scenarios';

export interface DemoContextValue {
  user: MeResponse | null;
  repository: OrderRepository;
  scenario: Scenario;
  setScenario: (s: Scenario) => void;
}
export const DemoContext = createContext<DemoContextValue | null>(null);
export function useDemo() {
  const value = useContext(DemoContext);
  if (!value) throw new Error('Demo provider missing');
  return value;
}
