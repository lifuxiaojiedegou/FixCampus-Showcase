import type {
  Action,
  CreateOrderRequest,
  CreateOrderResponse,
  MeResponse,
  OrderDetailResponse,
  OrderPageResponse,
  OrderQuery,
} from './contracts';

export interface OrderRepository {
  listOrders(
    user: MeResponse,
    query: OrderQuery,
    signal?: AbortSignal,
  ): Promise<OrderPageResponse>;
  getOrder(
    user: MeResponse,
    id: string,
    signal?: AbortSignal,
  ): Promise<OrderDetailResponse>;
  createOrder(
    user: MeResponse,
    input: CreateOrderRequest,
  ): Promise<CreateOrderResponse>;
  updateOrder(
    user: MeResponse,
    id: string,
    action: Action,
    input?: { workerId?: string; repairResult?: string | null },
  ): Promise<void>;
  todoCount(user: MeResponse): number;
}
