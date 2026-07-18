export const permissionCodes = [
  "USER_MANAGE",
  "RULE_MANAGE",
  "RESOURCE_MANAGE",
  "ORDER_CREATE",
  "ORDER_READ",
  "DISPATCH_EXECUTE",
  "MANUAL_REVIEW",
  "TASK_READ",
  "TASK_EXECUTE",
  "AUDIT_READ",
  "METRICS_READ",
  "DECISION_READ",
  "LOCATION_READ",
  "LOCATION_REPORT",
  "LOCATION_CORRECT",
  "LOCATION_EXPORT"
] as const;

export type PermissionCode = (typeof permissionCodes)[number];

const permissionsByRole: Record<string, readonly PermissionCode[]> = {
  SYSTEM_ADMIN: [
    "USER_MANAGE",
    "RULE_MANAGE",
    "RESOURCE_MANAGE",
    "ORDER_READ",
    "TASK_READ",
    "AUDIT_READ",
    "METRICS_READ",
    "DECISION_READ",
    "LOCATION_READ",
    "LOCATION_REPORT",
    "LOCATION_CORRECT",
    "LOCATION_EXPORT"
  ],
  DISPATCHER: ["ORDER_CREATE", "ORDER_READ", "DISPATCH_EXECUTE", "MANUAL_REVIEW", "TASK_READ", "TASK_EXECUTE", "DECISION_READ", "LOCATION_READ", "LOCATION_REPORT"],
  OPERATOR: ["RESOURCE_MANAGE", "ORDER_CREATE", "ORDER_READ", "TASK_READ", "METRICS_READ"],
  AUDITOR: ["AUDIT_READ", "METRICS_READ", "DECISION_READ"]
};

export function permissionsForRoles(roles: readonly string[]): Set<PermissionCode> {
  return new Set(roles.flatMap((role) => permissionsByRole[role] ?? []));
}
