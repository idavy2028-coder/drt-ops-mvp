import { reactive } from "vue";
import * as authApi from "../api/auth";
import type { AuthSession, CurrentUser } from "../api/types";
import { permissionsForRoles, type PermissionCode } from "./permissions";

type TestSession = Pick<AuthSession, "accessToken" | "user"> | null;

const state = reactive({
  accessToken: null as string | null,
  user: null as CurrentUser | null,
  restoreAttempted: false
});

export const authStore = {
  get accessToken() {
    return state.accessToken;
  },
  get user() {
    return state.user;
  },
  get authenticated() {
    return state.accessToken !== null && state.user !== null;
  },
  has(permission: PermissionCode): boolean {
    return state.user !== null && permissionsForRoles(state.user.roles).has(permission);
  },
  async login(username: string, password: string): Promise<void> {
    applySession(await authApi.login(username, password));
    state.restoreAttempted = true;
  },
  async restore(): Promise<boolean> {
    if (this.authenticated) {
      return true;
    }
    if (state.restoreAttempted) {
      return false;
    }
    state.restoreAttempted = true;
    return this.refresh();
  },
  async refresh(): Promise<boolean> {
    try {
      applySession(await authApi.refresh());
      state.restoreAttempted = true;
      return true;
    } catch {
      this.clearSessionForTest();
      return false;
    }
  },
  async logout(): Promise<void> {
    try {
      await authApi.logout();
    } finally {
      this.clearSessionForTest();
    }
  },
  setSessionForTest(session: TestSession): void {
    state.accessToken = session?.accessToken ?? null;
    state.user = session?.user ?? null;
    state.restoreAttempted = true;
  },
  clearSessionForTest(): void {
    state.accessToken = null;
    state.user = null;
    state.restoreAttempted = true;
  }
};

function applySession(session: AuthSession): void {
  state.accessToken = session.accessToken;
  state.user = session.user;
}
