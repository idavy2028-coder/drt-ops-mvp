import { request } from "./http";
import type { DispatchRuleSet, UUID } from "./types";

export type UpdateDispatchRuleSetInput = Omit<DispatchRuleSet, "id" | "name" | "enabled">;

export function listDispatchRuleSets(): Promise<DispatchRuleSet[]> {
  return request<DispatchRuleSet[]>("/api/dispatch-rule-sets");
}

export function updateDispatchRuleSet(id: UUID, input: UpdateDispatchRuleSetInput): Promise<DispatchRuleSet> {
  return request<DispatchRuleSet>(`/api/dispatch-rule-sets/${id}`, {
    method: "PUT",
    body: JSON.stringify(input)
  });
}
