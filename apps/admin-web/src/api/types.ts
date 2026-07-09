export type UUID = string;
export type IsoDateTime = string;
export type DecimalValue = number | string;

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

export interface Vehicle {
  id: UUID;
  plateNumber: string;
  vehicleType: string;
  capacity: number;
  currentStatus: string;
  fleetName: string;
  dispatchable: boolean;
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

export interface DispatchResult {
  rideOrderId: UUID;
  decision: "AUTO_DISPATCH" | "MANUAL_REVIEW" | "NO_FEASIBLE_PLAN";
  dispatchDecisionId: UUID;
  vehicleTaskId?: UUID;
}

export interface AuditLog {
  id: UUID;
  entityType: string;
  entityId: UUID;
  action: string;
  actorType: string;
  actorId: string;
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
