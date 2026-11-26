import { MockRouteNotFoundError } from '../errors';
import type { MeResponse, OrderQuery } from '../contracts';
import type { OrderRepository } from '../orderRepository';

// Explicit in-memory dispatch only. Unknown paths have no fallback.
export function mockRouter(
  repository: OrderRepository,
  method: string,
  path: string,
  user: MeResponse,
  query: OrderQuery,
) {
  if (method === 'GET' && path === '/orders')
    return repository.listOrders(user, query);
  const match = /^\/orders\/([1-9]\d*)$/.exec(path);
  if (method === 'GET' && match) return repository.getOrder(user, match[1]);
  return Promise.reject(new MockRouteNotFoundError());
}
