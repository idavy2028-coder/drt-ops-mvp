import { request } from "./http";
import type {
  OnboardConfigurationInput,
  OnboardConfigurationResult,
  OnboardSystemDetail,
  OnboardSystemPage
} from "./types";

export async function listOnboardSystems(page = 0, size = 20): Promise<OnboardSystemPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return request<OnboardSystemPage>(`/api/onboard-systems?${query.toString()}`);
}

export async function getOnboardSystem(vehicleId: string): Promise<OnboardSystemDetail> {
  return request<OnboardSystemDetail>(`/api/onboard-systems/${encodeURIComponent(vehicleId)}`);
}

export async function previewOnboardConfiguration(
  vehicleId: string,
  input: OnboardConfigurationInput
): Promise<OnboardConfigurationResult> {
  return configurationRequest(vehicleId, "configuration/preview", input);
}

export async function applyOnboardConfiguration(
  vehicleId: string,
  input: OnboardConfigurationInput
): Promise<OnboardConfigurationResult> {
  return configurationRequest(vehicleId, "configuration", input);
}

function configurationRequest(
  vehicleId: string,
  suffix: string,
  input: OnboardConfigurationInput
): Promise<OnboardConfigurationResult> {
  return request<OnboardConfigurationResult>(
    `/api/onboard-systems/${encodeURIComponent(vehicleId)}/${suffix}`,
    { method: "POST", body: JSON.stringify(input) }
  );
}
