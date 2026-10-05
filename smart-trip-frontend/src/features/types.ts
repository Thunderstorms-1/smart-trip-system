export type ItemType = "SCENIC" | "FOOD" | "HOTEL" | "TRAFFIC" | "OTHER";
export interface PublicAuthor {
  id: number;
  nickname: string;
  avatar?: string;
  bio?: string;
  homeCity?: string;
}
export interface PostPage {
  items: Post[];
  nextCursor?: string;
  hasMore: boolean;
}
export interface Profile {
  id: number;
  username?: string;
  nickname: string;
  avatar?: string;
  email?: string;
  bio?: string;
  homeCity?: string;
  travelPreferences?: string;
  aiQuotaDaily?: number;
  aiQuotaUsed?: number;
}
export interface Item {
  id?: number;
  dayId?: number;
  tripId?: number;
  environmentType?: "INDOOR" | "OUTDOOR" | "UNKNOWN";
  scheduleLocked?: boolean;
  itemType: ItemType;
  itemName: string;
  startTime?: string;
  costTimeHours?: number;
  costEstimate: number;
  aiTips?: string;
  transportTips?: string;
  poiId?: string;
  coordinateSystem?: string;
  longitude?: number;
  latitude?: number;
  address?: string;
  sortOrder?: number;
}
export interface Day {
  id?: number;
  tripId?: number;
  dayIndex?: number;
  dayDate?: string;
  dayTheme?: string;
  daySummary?: string;
  items: Item[];
}
export interface Trip {
  id?: number;
  userId?: number;
  title: string;
  destination: string;
  startDate?: string;
  totalDays?: number;
  totalBudget?: number;
  tripStyle?: string;
  travelGroup?: string;
  summary?: string;
  isPublic?: number;
  version?: number;
  days: Day[];
}
export interface Post {
  id: number;
  authorId: number;
  authorName: string;
  authorAvatar?: string;
  title: string;
  destination: string;
  totalDays: number;
  totalBudget: number;
  summary?: string;
  likeCount: number;
  favoriteCount: number;
  commentCount: number;
  cloneCount: number;
  liked?: boolean;
  favorited?: boolean;
  trip?: Trip;
}
export interface Comment {
  id: number;
  authorId: number;
  authorName: string;
  content: string;
  createdAt: string;
}
export type CapabilityStatus =
  | "READY"
  | "DISABLED"
  | "QUOTA_UNVERIFIED"
  | "KEY_MISSING"
  | "BROWSER_KEY_MISSING";

export interface Capabilities {
  ai: boolean;
  geo: boolean;
  map: boolean;
  status?: {
    ai: CapabilityStatus;
    geo: CapabilityStatus;
    map: CapabilityStatus;
  };
  notice: string;
}
export interface Task {
  id: string;
  type: "GENERATE" | "MODIFY" | "ADJUST";
  status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "PREVIEW" | "APPLIED" | "FAILED";
  tripId?: number;
  baseVersion?: number;
  result?: Trip;
  errorMessage?: string;
  proposal?: AdjustmentProposal;
}
export interface Expense {
  id?: number;
  category: string;
  expenseName: string;
  amount: number;
  expenseDate: string;
  remark?: string;
  sourceItemId?: number;
}
export interface ExpenseSummary {
  items: Expense[];
  actual: number;
  estimated: number;
}
export interface Packing {
  id: number;
  itemName: string;
  isChecked: number;
}
export interface Revision {
  id: number;
  version: number;
  create_time: string;
}
export interface Share {
  id: number;
  revoked: number;
  createdAt: string;
}
export interface Poi {
  poiId: string;
  name: string;
  address: string;
  location: string;
  adcode: string;
  provider: string;
}
export interface RoutePoint {
  itemId: number;
  name: string;
  resolved: boolean;
  location?: string;
}
export interface RouteLeg {
  from: number;
  to: number;
  available: boolean;
  reason?: string;
  distanceMeters?: string;
  durationSeconds?: string;
  polylines?: string[];
}
export interface RouteData {
  retrievedAt: string;
  points: RoutePoint[];
  legs: RouteLeg[];
  tripVersion: number;
  mode: string;
}
export interface Weather {
  available: boolean;
  reason?: string;
  date: string;
  city?: string;
  dayWeather?: string;
  nightWeather?: string;
  high?: string;
  low?: string;
  issuedAt?: string;
  retrievedAt?: string;
}

export interface AnalysisIssue {
  id: string;
  code: string;
  severity: string;
  dayId: number;
  itemIds: number[];
  message: string;
  evidenceIds: string[];
}
export interface AnalysisSource {
  id: string;
  dayId: number;
  kind: "WEATHER" | "ROUTE";
  status: string;
  summary: string;
  retrievedAt?: string;
  issuedAt?: string;
}
export interface AnalysisReport {
  id: string;
  tripId: number;
  baseVersion: number;
  revision: number;
  dayIds: number[];
  mode: string;
  expiresAt: string;
  issues: AnalysisIssue[];
  sources: AnalysisSource[];
}
export interface AdjustmentChange {
  itemId: number;
  itemName: string;
  fromDayId: number;
  toDayId: number;
  fromDayIndex: number;
  toDayIndex: number;
  fromOrder: number;
  toOrder: number;
  fromTime?: string;
  toTime?: string;
  kinds: string[];
}
export interface AdjustmentProposal {
  dayIds: number[];
  changes: AdjustmentChange[];
  reasons: { itemIds: number[]; evidenceIds: string[]; text: string }[];
  remainingIssues: AnalysisIssue[];
  unverifiedRouteDayIds: number[];
  sources: AnalysisSource[];
  expiresAt?: string;
}
