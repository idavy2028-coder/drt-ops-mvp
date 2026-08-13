import { readonly, reactive } from "vue";
import { getOperationsDashboard, getOperationsSummary } from "../api/metrics";
import type { OperationsDashboard, OperationsSummary } from "../api/types";
import { userMessage } from "../api/errors";

interface OperationsState {
  loading: boolean;
  refreshing: boolean;
  error: string;
  summary: OperationsSummary | null;
  dashboard: OperationsDashboard | null;
}

const state = reactive<OperationsState>({
  loading: false,
  refreshing: false,
  error: "",
  summary: null,
  dashboard: null
});

export function useOperationsStore() {
  async function loadSummary(date?: string) {
    state.loading = true;
    state.error = "";
    try {
      state.summary = await getOperationsSummary(date);
    } catch (error) {
      state.error = userMessage(error, "运营数据加载失败");
    } finally {
      state.loading = false;
    }
  }

  async function loadDashboard(endDate: string): Promise<boolean> {
    if (state.dashboard === null) {
      state.loading = true;
    } else {
      state.refreshing = true;
    }
    state.error = "";
    try {
      state.dashboard = await getOperationsDashboard(endDate);
      return true;
    } catch (error) {
      state.error = userMessage(error, "运营数据加载失败");
      return false;
    } finally {
      state.loading = false;
      state.refreshing = false;
    }
  }

  return {
    state: readonly(state),
    loadSummary,
    loadDashboard
  };
}
