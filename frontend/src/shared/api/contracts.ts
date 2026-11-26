// PROVISIONAL — M4.0.3 candidate contract; Mock responses are not a backend.
export type Role = 'STUDENT' | 'ADMIN' | 'WORKER';
export type RolePath = 'student' | 'admin' | 'worker';
export type OrderStatus =
  | 'PENDING_ASSIGNMENT'
  | 'PENDING_REPAIR'
  | 'IN_PROGRESS'
  | 'PENDING_CONFIRMATION'
  | 'COMPLETED'
  | 'CANCELLED';
export type Category =
  | 'WATER_ELECTRICITY'
  | 'DOORS_FURNITURE'
  | 'NETWORK'
  | 'AIR_CONDITIONING'
  | 'OTHER';
export type Action = 'CANCEL' | 'CONFIRM' | 'ASSIGN' | 'START' | 'COMPLETE';
export interface MeResponse {
  id: string;
  username: string;
  displayName: string;
  role: Role;
}
export interface CreateOrderRequest {
  location: string;
  category: Category;
  description: string;
}
export interface CreateOrderResponse {
  id: string;
  status: 'PENDING_ASSIGNMENT';
}
export interface OrderItem {
  id: string;
  location: string;
  category: Category;
  status: OrderStatus;
  createdAt: string;
}
export interface HistoryEntry {
  action: string;
  occurredAt: string | null;
  actorRole: Role | null;
  actorName: string | null;
  detail: string | null;
  recorded: boolean;
}
interface DetailBase extends OrderItem {
  description: string;
  repairResult: string | null;
  completedAt: string | null;
  history: { entries: HistoryEntry[]; incomplete: boolean };
  availableActions: Action[];
}
export type OrderDetailResponse = DetailBase &
  (
    | { viewRole: 'STUDENT'; workerName?: string }
    | { viewRole: 'ADMIN'; student: MeResponse; worker: MeResponse | null }
    | { viewRole: 'WORKER'; studentName: string }
  );
export interface OrderQuery {
  page: number;
  size: number;
  sort: 'createdAt,desc' | 'createdAt,asc';
  status: OrderStatus | '';
  q: string;
}
export interface OrderPageResponse {
  items: OrderItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sort: OrderQuery['sort'];
}
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  code: string;
  message: string;
  requestId: string;
  fieldErrors?: { field: string; message: string }[];
}
export type StatsWindowDays = 7 | 14 | 30;
export interface StatusCountItem {
  status: OrderStatus;
  count: number;
}
export interface CategoryCountItem {
  category: Category;
  count: number;
}
export interface DailyTrendItem {
  date: string;
  createdCount: number;
  repairCompletedCount: number;
  confirmedCount: number;
}
export interface TurnaroundMetrics {
  sampleCount: number;
  missingEventCount: number;
  averageMinutes: number | null;
  minMinutes: number | null;
  maxMinutes: number | null;
}
export interface LocationAggregationInfo {
  supported: boolean;
  reason: string;
}
export interface AdminStatisticsResponse {
  generatedAt: string;
  timezone: string;
  windowDays: StatsWindowDays;
  windowStartDate: string;
  windowEndDate: string;
  totalOrders: number;
  statusCounts: StatusCountItem[];
  categoryCounts: CategoryCountItem[];
  dailyTrend: DailyTrendItem[];
  durations: {
    repairTurnaround: TurnaroundMetrics;
    confirmationTurnaround: TurnaroundMetrics;
  };
  locationAggregation: LocationAggregationInfo;
}
export type AttachmentUploaderRole = 'STUDENT' | 'WORKER';
export type AttachmentContentType = 'image/png' | 'image/jpeg';
export interface OrderAttachmentItem {
  id: string;
  orderId: string;
  uploaderRole: AttachmentUploaderRole;
  uploaderName: string;
  originalFilename: string;
  contentType: AttachmentContentType;
  sizeBytes: number;
  width: number;
  height: number;
  createdAt: string;
  contentUrl: string;
  canDelete: boolean;
}
export interface OrderAttachmentLimits {
  maxFileBytes: number;
  maxWidth: number;
  maxHeight: number;
  maxPixels: number;
  maxStudentAttachments: number;
  maxWorkerAttachments: number;
  allowedContentTypes: AttachmentContentType[];
}
export interface OrderAttachmentListResponse {
  orderId: string;
  items: OrderAttachmentItem[];
  limits: OrderAttachmentLimits;
  canUpload: boolean;
}
export type NotificationEventType =
  | 'ORDER_SUBMITTED'
  | 'ORDER_ASSIGNED'
  | 'REPAIR_STARTED'
  | 'REPAIR_COMPLETED'
  | 'ORDER_CONFIRMED'
  | 'ORDER_CANCELLED';
export interface NotificationItem {
  id: string;
  orderId: string;
  eventType: NotificationEventType;
  title: string;
  body: string;
  read: boolean;
  readAt: string | null;
  createdAt: string;
}
export interface NotificationPageResponse {
  items: NotificationItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  unreadCount: number;
}
export interface UnreadCountResponse {
  unreadCount: number;
}
export interface NotificationReadResponse {
  id: string;
  read: true;
  readAt: string;
  unreadCount: number;
}
export interface NotificationReadAllResponse {
  markedCount: number;
  unreadCount: number;
}
