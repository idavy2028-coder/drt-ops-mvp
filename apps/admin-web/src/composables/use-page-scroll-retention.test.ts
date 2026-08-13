// @vitest-environment jsdom
import { cleanup, render } from "@testing-library/vue";
import { KeepAlive, defineComponent, h, nextTick, ref } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { usePageScrollRetention } from "./usePageScrollRetention";

describe("usePageScrollRetention", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("缓存页重新激活时恢复离开前的窗口滚动位置", async () => {
    const showPage = ref(true);
    const scrollTo = vi.fn();
    Object.defineProperty(window, "scrollTo", { configurable: true, value: scrollTo });
    Object.defineProperty(window, "scrollY", { configurable: true, value: 0 });

    const CachedPage = defineComponent({
      name: "CachedPage",
      setup() {
        usePageScrollRetention();
        return () => h("p", "缓存页面");
      }
    });
    const OtherPage = defineComponent({
      name: "OtherPage",
      setup: () => () => h("p", "其他页面")
    });
    const Host = defineComponent({
      setup() {
        return () => h(
          KeepAlive,
          null,
          () => showPage.value ? h(CachedPage, { key: "cached" }) : h(OtherPage, { key: "other" })
        );
      }
    });

    render(Host);
    await nextTick();
    scrollTo.mockClear();
    Object.defineProperty(window, "scrollY", { configurable: true, value: 420 });

    showPage.value = false;
    await nextTick();
    Object.defineProperty(window, "scrollY", { configurable: true, value: 0 });
    showPage.value = true;
    await nextTick();
    await nextTick();

    expect(scrollTo).toHaveBeenCalledWith({ top: 420, behavior: "auto" });
  });
});
