import { request } from "./http";
import type { DispatchRuleSet, UUID } from "./types";

export type UpdateDispatchRuleSetInput = Omit<DispatchRuleSet, "id" | "name" | "enabled">;
export type CreateDispatchRuleSetInput = UpdateDispatchRuleSetInput & { name: string };

export function listDispatchRuleSets(): Promise<DispatchRuleSet[]> {
  return request<DispatchRuleSet[]>("/api/dispatch-rule-sets");
}

export function updateDispatchRuleSet(id: UUID, input: UpdateDispatchRuleSetInput): Promise<DispatchRuleSet> {
  return request<DispatchRuleSet>(`/api/dispatch-rule-sets/${id}`, {
    method: "PUT",
    body: JSON.stringify(input)
  });
}

export function createDispatchRuleSet(input: CreateDispatchRuleSetInput): Promise<DispatchRuleSet> {
  return request<DispatchRuleSet>("/api/dispatch-rule-sets", {
    method: "POST",
    body: JSON.stringify(input)
  });
}
