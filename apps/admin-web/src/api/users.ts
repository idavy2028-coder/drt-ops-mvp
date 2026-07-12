import { request } from "./http";
import type { UserAccount } from "./types";

export function listUsers(): Promise<UserAccount[]> { return request("/api/users"); }
export function createUser(input: { username: string; displayName: string; temporaryPassword: string; roles: string[] }): Promise<UserAccount> { return request("/api/users", { method: "POST", body: JSON.stringify(input) }); }
export function updateUserRoles(userId: string, roles: string[]): Promise<UserAccount> { return request(`/api/users/${userId}/roles`, { method: "PUT", body: JSON.stringify({ roles }) }); }
export function setUserEnabled(userId: string, enabled: boolean): Promise<void> { return request(`/api/users/${userId}/${enabled ? "enable" : "disable"}`, { method: "POST" }); }
export function resetPassword(userId: string, temporaryPassword: string): Promise<void> { return request(`/api/users/${userId}/reset-password`, { method: "POST", body: JSON.stringify({ temporaryPassword }) }); }
