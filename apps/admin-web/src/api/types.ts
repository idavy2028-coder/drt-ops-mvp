export type UUID = string;
export type IsoDateTime = string;
export type DecimalValue = number | string;

export interface CurrentUser {
  id: UUID;
  username: string;
  roles: string[];
  mustChangePassword: boolean;
}

export interface AuthSession {
  accessToken: string;
  expiresAt: IsoDateTime;
  user: CurrentUser;
}

export interface UserAccount {
  id: UUID;
  username: string;
  displayName: string;
  roles: string[];
  enabled: boolean;
  mustChangePassword: boolean;
}

export interface ServiceArea {
  id: UUID;
  name: string;
  boundary: string;
  serviceStart: string;
  serviceEnd: string;
  ruleSetId: UUID;
  enabled: boolean;
}

export interface VirtualStop {
  id: UUID;
  serviceAreaId: UUID;
  name: string;
  location: string;
  serviceRadiusMeters: number;
  boardingEnabled: boolean;
  alightingEnabled: boolean;
  safetyNote: string;
  enabled: boolean;
}

export interface LocationCandidate {
  longitude?: number;
  latitude?: number;
  standardizedAddress: string;
  virtualStopId?: UUID;
  providerDegraded?: boolean;
  outsideServiceArea?: boolean;
}

export interface LocationReportInput extends Omit<LocationCandidate, "longitude" | "latitude"> {
  longitude: number;
  latitude: number;
  driverReportedAt: IsoDateTime;
  note?: string;
  idempotencyKey: UUID;
}

export interface LocationPickerProvider {
  search(keyword: string): Promise<LocationCandidate[]>;
  pickOnMap(container: HTMLElement, initial?: LocationCandidate): Promise<LocationCandidate>;
}

export interface MapProviderStatus {
  provider: string;
  enabled: boolean;
  degradedReason?: string;
  coordinateSystem: "GCJ-02";
}

export interface VehicleLocationView {
  id: UUID;
  vehicleId: UUID;
  vehicleTaskId?: UUID;
  taskStopId?: UUID;
  virtualStopId?: UUID;
  eventType: string;
  longitude: DecimalValue;
  latitude: DecimalValue;
  standardizedAddress: string;
  source: string;
  coordinateSystem: string;
  driverReportedAt: IsoDateTime;
  recordedAt: IsoDateTime;
  recordedBy: UUID;
  correctsEventId?: UUID;
  snapshotApplied: boolean;
  outsideServiceArea?: boolean;
}

export type VehicleLocationEventView = VehicleLocationView;

export interface VehicleLocationEventFilters {
  vehicleId?: UUID;
  taskId?: UUID;
  date?: string;
  eventType?: string;
  from?: IsoDateTime;
  to?: IsoDateTime;
}

export interface VehicleLocationSnapshot {
  longitude: DecimalValue;
  latitude: DecimalValue;
  standardizedAddress: string;
  source: string;
  coordinateSystem: string;
  driverReportedAt: IsoDateTime;
  recordedAt: IsoDateTime;
  eventId: UUID;
  vehicleTaskId?: UUID;
  outsideServiceArea?: boolean;
}

export interface VehicleLocationSnapshotItem {
  vehicleId: UUID;
  plateNumber: string;
  currentStatus: string;
  latestLocation: VehicleLocationSnapshot;
}

export interface Vehicle {
  id: UUID;
  plateNumber: string;
  vehicleType: string;
  capacity: number;
  currentStatus: string;
  fleetName: string;
  dispatchable: boolean;
  latestLocation?: VehicleLocationSnapshot;
}

export interface Driver {
  id: UUID;
  name: string;
  phone: string;
  qualificationStatus: string;
  shiftStart?: IsoDateTime;
  shiftEnd?: IsoDateTime;
  currentStatus: string;
  fleetName: string;
}

export interface DispatchRuleSet {
  id: UUID;
  name: string;
  maxWaitMinutes: number;
  maxDetourMinutes: number;
  bookingWindowMinutes: number;
  autoDispatchScoreThreshold: DecimalValue;
  manualReviewScoreThreshold: DecimalValue;
  waitWeight: DecimalValue;
  detourWeight: DecimalValue;
  stabilityWeight: DecimalValue;
  utilizationWeight: DecimalValue;
  insertionPolicy: string;
  enabled: boolean;
}

export interface RideOrder {
  id: UUID;
  passengerName: string;
  passengerPhone: string;
  passengerCount: number;
  requestType: string;
  originLng: DecimalValue;
  originLat: DecimalValue;
  destinationLng: DecimalValue;
  destinationLat: DecimalValue;
  boardingStopId?: UUID;
  alightingStopId?: UUID;
  requestedDepartureAt: IsoDateTime;
  estimatedBoardingAt?: IsoDateTime;
  estimatedArrivalAt?: IsoDateTime;
  status: string;
}

export interface TaskStop {
  id: UUID;
  virtualStopId: UUID;
  rideOrderId?: UUID;
  sequenceNumber: number;
  stopType: string;
  plannedArrivalAt: IsoDateTime;
  actualArrivalAt?: IsoDateTime;
  status: string;
}

export interface VehicleTask {
  id: UUID;
  vehicleId: UUID;
  driverId: UUID;
  status: string;
  plannedStartAt: IsoDateTime;
  stops: TaskStop[];
}

export interface TaskActionResponse {
  task: VehicleTask;
  locationEvent?: VehicleLocationView;
  snapshotApplied: boolean;
  warnings: string[];
  replayed: boolean;
}

export interface DispatchResult {
  rideOrderId: UUID;
  decision: "AUTO_DISPATCH" | "MANUAL_REVIEW" | "NO_FEASIBLE_PLAN";
  dispatchDecisionId: UUID;
  vehicleTaskId?: UUID;
}

export interface ManualReviewQueueItem {
  decisionId: UUID;
  orderId: UUID;
  passengerName: string;
  passengerCount: number;
  requestedDepartureAt: IsoDateTime;
  bestVehicleId?: UUID;
  candidateCount: number;
}

export interface AuditLog {
  id: UUID;
  entityType: string;
  entityId: UUID;
  action: string;
  actorType: string;
  actorId: string;
  actorDisplayName?: string;
  reason?: string;
  metadataJson: string;
  createdAt: IsoDateTime;
}

export interface OperationsSummary {
  orderCount: number;
  confirmationRate: DecimalValue;
  autoDispatchRate: DecimalValue;
  manualReviewRate: DecimalValue;
  averageWaitMinutes: DecimalValue;
  averageDetourMinutes: DecimalValue;
  taskCompletionRate: DecimalValue;
  exceptionCloseRate: DecimalValue;
  vehicleUtilizationRate: DecimalValue;
}
