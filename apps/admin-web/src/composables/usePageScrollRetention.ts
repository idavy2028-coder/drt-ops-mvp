import { nextTick, onActivated, onDeactivated } from "vue";

export function usePageScrollRetention(): void {
  let scrollTop = 0;

  onDeactivated(() => {
    scrollTop = window.scrollY;
  });

  onActivated(() => {
    void nextTick(() => {
      window.scrollTo({ top: scrollTop, behavior: "auto" });
    });
  });
}
