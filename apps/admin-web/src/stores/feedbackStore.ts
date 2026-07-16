import { readonly, reactive } from "vue";

export type FeedbackTone = "success" | "error" | "info";

interface FeedbackItem {
  id: number;
  tone: FeedbackTone;
  message: string;
}

const state = reactive({
  items: [] as FeedbackItem[]
});
let nextId = 1;

export const feedbackStore = {
  get items() {
    return readonly(state.items);
  },
  success(message: string) {
    push("success", message);
  },
  error(message: string) {
    push("error", message, 6000);
  },
  info(message: string) {
    push("info", message);
  },
  dismiss(id: number) {
    state.items = state.items.filter((item) => item.id !== id);
  }
};

function push(tone: FeedbackTone, message: string, duration = 3600) {
  const id = nextId++;
  state.items.push({ id, tone, message });
  window.setTimeout(() => feedbackStore.dismiss(id), duration);
}
