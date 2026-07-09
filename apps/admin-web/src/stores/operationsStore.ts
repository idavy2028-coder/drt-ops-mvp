import { readonly, reactive } from "vue";
import { getOperationsSummary } from "../api/metrics";
import type { OperationsSummary } from "../api/types";

interface OperationsState {
  loading: boolean;
  error: string;
  summary: OperationsSummary | null;
}

const state = reactive<OperationsState>({
  loading: false,
  error: "",
  summary: null
});

export function useOperationsStore() {
  async function loadSummary(date?: string) {
    state.loading = true;
    state.error = "";
    try {
      state.summary = await getOperationsSummary(date);
    } catch (error) {
      state.error = error instanceof Error ? error.message : "运营数据加载失败";
    } finally {
      state.loading = false;
    }
  }

  return {
    state: readonly(state),
    loadSummary
  };
}
